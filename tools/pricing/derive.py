"""
Derive buy/sell prices for every worth.json item from the extracted recipe graph
+ anchors. Report-only by default; pass --write to update worth.json (with backup).

  python tools/pricing/derive.py            # report + sanity table, writes nothing
  python tools/pricing/derive.py --write     # writes worth.json
"""
import json, os, sys, collections, math

HERE = os.path.dirname(__file__)
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
sys.path.insert(0, HERE)
import tiers as T

MARKUP = 1.05
INF = math.inf


def load():
    worth = json.load(open(os.path.join(ROOT, "worth.json")))
    recipes = json.load(open(os.path.join(HERE, "work", "recipes.json")))
    tags = json.load(open(os.path.join(HERE, "work", "tags.json")))
    return worth, recipes, tags


def build():
    worth, recipes, tags = load()
    cat_of = {}
    for c, d in worth["categories"].items():
        for k in d:
            cat_of[k] = c
    by_out = collections.defaultdict(list)
    for r in recipes:
        by_out[r["out"]].append(r)
    return worth, by_out, tags, cat_of


class Deriver:
    def __init__(self, by_out, tags, cat_of):
        self.by_out = by_out
        self.tags = tags
        self.cat_of = cat_of
        self.memo = {}
        self.stack = set()
        self.via = {}        # item -> "anchor" | "recipe:<type>"
        self.best_inputs = {} # item -> [(concrete_id, count)] of winning recipe, or None
        self.sell_memo = {}
        self.sell_stack = set()

    def resolve_concrete(self, ing):
        """(cheapest price, concrete id) for an id / #tag / {choices:[...]}."""
        if isinstance(ing, dict) and "choices" in ing:
            best, who = INF, None
            for c in ing["choices"]:
                p, w = self.resolve_concrete(c)
                if p < best:
                    best, who = p, w
            return best, who
        if isinstance(ing, str) and ing.startswith("#"):
            members = self.tags.get(ing)
            if not members:
                return INF, None
            best, who = INF, None
            for m in members:
                p, w = self.resolve_concrete(m)
                if p < best:
                    best, who = p, w
            return best, who
        if isinstance(ing, str):
            return self.price(ing), ing
        return INF, None

    def resolve_ingredient(self, ing):
        return self.resolve_concrete(ing)[0]

    def price(self, item):
        if item in self.memo:
            return self.memo[item]
        # EXACT overrides win, unconditionally (currency + true one-offs)
        if item in T.EXACT:
            self.memo[item] = float(T.EXACT[item])
            self.via[item] = "anchor"
            return self.memo[item]
        # explicitly-classified roots (raw mats) win over recipes, so decompose
        # recipes (e.g. block -> 9 ingots) can't undervalue them via a spurious root
        if item in T.CELL:
            self.memo[item] = float(T.formula_price(T.CELL[item]))
            self.via[item] = "anchor"
            return self.memo[item]
        if item in self.stack:
            return INF  # cycle
        self.stack.add(item)

        cat = self.cat_of.get(item)
        best = INF
        best_type = None
        best_inputs = None
        for r in self.by_out.get(item, []):
            cost = 0.0
            ok = True
            resolved = []
            for inp in r["inputs"]:
                if inp.get("catalyst"):
                    continue
                c, who = self.resolve_concrete(inp["id"])
                if not math.isfinite(c):
                    ok = False
                    break
                cnt_i = inp.get("count", 1)
                cost += c * cnt_i
                resolved.append((who, cnt_i))
            if not ok:
                continue
            cnt = max(1, r.get("count", 1))
            unit = cost / cnt * MARKUP
            if unit < best:
                best = unit
                best_type = r["type"]
                # per-output share of the inputs (fractional counts ok for sell)
                best_inputs = [(w, n / cnt) for (w, n) in resolved]

        self.stack.discard(item)
        if math.isfinite(best):
            self.memo[item] = best
            self.via[item] = "recipe:" + best_type
            self.best_inputs[item] = best_inputs
        else:
            # no recipe resolved -> treat as root, price by effort×rarity formula
            self.memo[item] = T.root_buy(item, cat)
            self.via[item] = "anchor"
            self.best_inputs[item] = None
        return self.memo[item]

    def sell(self, item):
        """Crafted item sell = sum of ingredient sells (arbitrage-free); root
        sell = rarity-scaled fraction of buy. Capped just under buy."""
        if item in self.sell_memo:
            return self.sell_memo[item]
        if item in T.CURRENCY_FACE:
            self.sell_memo[item] = float(T.CURRENCY_FACE[item])
            return self.sell_memo[item]
        buy = self.price(item)
        inputs = self.best_inputs.get(item)
        if not inputs or item in self.sell_stack:   # root or cycle
            s = T.root_sell(item, buy, self.cat_of.get(item))
        else:
            self.sell_stack.add(item)
            s = 0.0
            for who, n in inputs:
                s += self.sell(who) * n
            self.sell_stack.discard(item)
        s = min(s, buy * 0.95)
        self.sell_memo[item] = max(round(s, 2), T.SELL_FLOOR)
        return self.sell_memo[item]


def base_copper(item):
    """Oxidation/wax variants share material value with the plain form:
    waxed_exposed_cut_copper_stairs -> cut_copper_stairs. Returns base id or None."""
    if ":" not in item:
        return None
    ns, path = item.split(":", 1)
    p = path
    for tok in ("waxed_", "exposed_", "weathered_", "oxidized_"):
        p = p.replace(tok, "")
    if p == path:
        return None
    if p == "copper":           # bare block: exposed_copper -> copper_block
        p = "copper_block"
    return f"{ns}:{p}"


def round_buy(v):
    if v is None:
        return None
    if v >= 1000:
        return int(round(v / 10.0) * 10)   # nearest 10
    if v >= 100:
        return int(round(v))
    if v >= 10:
        return round(v, 1)
    return round(v, 2)


def main():
    write = "--write" in sys.argv
    worth, by_out, tags, cat_of = build()
    d = Deriver(by_out, tags, cat_of)

    # price everything
    priced = {}
    for c, items in worth["categories"].items():
        for k in items:
            buy = round_buy(d.price(k))
            sell = d.sell(k)
            b = base_copper(k)                     # oxidation/wax == plain form
            if b and b != k:
                bb = round_buy(d.price(b))
                if bb:
                    buy = bb
                    sell = d.sell(b)
            priced[k] = (buy, sell)

    # diagnostics
    via_counts = collections.Counter(d.via.get(k, "?") for k in priced)
    anchored = [k for k in priced if d.via.get(k) == "anchor"]
    print("=== price source ===")
    for v, n in via_counts.most_common(12):
        print(f"  {n:5}  {v}")
    print(f"\ntotal priced: {len(priced)}   anchored: {len(anchored)}")

    # sanity table: headline + derived examples
    sample = [
        "minecraft:cobblestone", "minecraft:dirt", "minecraft:oak_log", "minecraft:oak_planks",
        "minecraft:stick", "minecraft:coal", "minecraft:iron_ingot", "minecraft:iron_block",
        "minecraft:iron_door", "minecraft:hopper", "minecraft:chest", "minecraft:gold_ingot",
        "minecraft:diamond", "minecraft:diamond_block", "minecraft:diamond_pickaxe",
        "minecraft:netherite_ingot", "minecraft:netherite_pickaxe", "minecraft:netherite_sword",
        "minecraft:beacon", "minecraft:enchanted_golden_apple", "minecraft:nether_star",
        "minecraft:bread", "minecraft:cake", "minecraft:bookshelf", "minecraft:anvil",
        "minecraft:glass", "minecraft:stone_bricks", "minecraft:oak_stairs",
        "create:andesite_alloy", "create:shaft", "create:cogwheel", "create:precision_mechanism",
        "farmersdelight:cooking_pot", "refurbished_furniture:oak_chair",
    ]
    print("\n=== sanity table (buy / sell / via) ===")
    for s in sample:
        if s in priced:
            b, se = priced[s]
            print(f"  {s:42} buy {str(b):>8}   sell {str(se):>7}   {d.via.get(s)}")
        else:
            print(f"  {s:42} (not in worth.json)")

    if write:
        bak = os.path.join(ROOT, "worth.json.bak2")
        json.dump(worth, open(bak, "w"))  # safety copy of pre-write structure
        for c, items in worth["categories"].items():
            for k in list(items):
                b, se = priced[k]
                items[k] = {"buy": b, "sell": se}
        with open(os.path.join(ROOT, "worth.json"), "w") as f:
            json.dump(worth, f, indent=2)
            f.write("\n")
        print(f"\nWROTE worth.json ({len(priced)} items). backup: worth.json.bak2")
    else:
        print("\n(report only — pass --write to update worth.json)")


if __name__ == "__main__":
    main()
