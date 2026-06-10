"""
Derive buy/sell prices for every worth.json item from the extracted recipe graph
+ tiers. Report-only by default; pass --write to update worth.json (with backup).

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


def merge_inputs(r):
    """Non-catalyst inputs merged by identical ingredient spec -> [(ing, count)].
    (Shapeless recipes record '9 ingots' as nine count-1 entries.)"""
    acc = {}
    for i in r["inputs"]:
        if i.get("catalyst"):
            continue
        key = json.dumps(i["id"], sort_keys=True)
        if key in acc:
            acc[key] = (acc[key][0], acc[key][1] + i.get("count", 1))
        else:
            acc[key] = (i["id"], i.get("count", 1))
    return list(acc.values())


def concrete_ids(ing, tags, _seen=None):
    """Set of concrete item ids an ingredient spec (id / #tag / choices) can be."""
    out, seen = set(), _seen if _seen is not None else set()

    def go(v):
        if isinstance(v, dict) and "choices" in v:
            for c in v["choices"]:
                go(c)
        elif isinstance(v, str) and v.startswith("#"):
            if v not in seen:
                seen.add(v)
                for m in tags.get(v, []):
                    go(m)
        elif isinstance(v, str):
            out.add(v)

    go(ing)
    return out


def prune_decompose(by_out, tags):
    """Break reciprocal recipe pairs (1 B -> k U  and  m U -> 1 B). With both
    directions in the graph, pricing depends on evaluation order: the cycle
    guard breaks the recursion at an arbitrary point and whichever item is
    inside gets memoized as a formula root (the 'spurious root' collapse the
    CELL pins were patching pair by pair). Which direction to keep depends on
    which item is the primary one:

      k >= 3 (storage blocks, ingot->nuggets, crates): the UNIT is primary -
        drop the decompose, keep compose (block = 9 x unit); the unit prices
        from its own source or root cell.
      k == 2 (slabs, half-mats, cabbage->leaves): the BLOCK is primary - drop
        the compose, keep decompose (slab = block / 2); the block prices from
        its own source.

    Returns the list of dropped (out, input) pairs for diagnostics."""
    dropped, drop_ids = [], set()
    for u in list(by_out):
        for r in by_out[u]:
            ins = merge_inputs(r)
            k = max(1, r.get("count", 1))
            if len(ins) != 1 or ins[0][1] != 1 or k < 2:
                continue
            for b in concrete_ids(ins[0][0], tags):
                for r2 in by_out.get(b, []):
                    ins2 = merge_inputs(r2)
                    if (max(1, r2.get("count", 1)) == 1 and len(ins2) == 1
                            and ins2[0][1] >= 2
                            and u in concrete_ids(ins2[0][0], tags)):
                        if k >= 3:
                            drop_ids.add(id(r))      # drop decompose B -> kU
                            dropped.append((u, ins[0][0]))
                        else:
                            drop_ids.add(id(r2))     # drop compose mU -> B
                            dropped.append((b, u))
    for u in list(by_out):
        by_out[u] = [r for r in by_out[u] if id(r) not in drop_ids]
    return dropped


def build():
    worth, recipes, tags = load()
    cat_of = {}
    for c, d in worth["categories"].items():
        for k in d:
            cat_of[k] = c
    by_out = collections.defaultdict(list)
    for r in recipes:
        by_out[r["out"]].append(r)
    pruned = prune_decompose(by_out, tags)
    if pruned:
        print(f"(pruned {len(pruned)} reciprocal decompose recipes from the pricing graph)")
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
            # chance-based outputs (e.g. sequenced assembly @0.8): expected cost
            # per success is cost / chance
            unit = cost / cnt / r.get("chance", 1.0) * MARKUP
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
        if item in T.EXACT_SELL:
            self.sell_memo[item] = float(T.EXACT_SELL[item])
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


def clamp_sells(priced):
    """Cap every sell at 95% of the item's cheapest shop-acquisition cost over
    ALL in-game conversions (work/conversions.json: construction recipes plus
    the cutting/washing/... types pricing ignores). This is the arbitrage.py
    invariant enforced at generation time: whatever a player can replicate from
    shop-bought goods must pay out less than it cost. Currency stays at face."""
    import arbitrage as AR
    conv_path = os.path.join(HERE, "work", "conversions.json")
    if not os.path.exists(conv_path):
        print("(no work/conversions.json - sell clamp skipped; run extract.py)")
        return 0
    conv = json.load(open(conv_path))
    tags = json.load(open(os.path.join(HERE, "work", "tags.json")))
    buy = dict(AR.CURRENCY)
    for k, (b, s) in priced.items():
        if b is not None:
            buy[k] = float(b)
    A = AR.acquisition(buy, conv, tags)
    clamped = 0
    for k, (b, s) in priced.items():
        if k in AR.CURRENCY or s is None:
            continue
        a = A.get(k, math.inf)
        if math.isfinite(a) and s > a * 0.95:
            priced[k] = (b, max(round(a * 0.95, 2), T.SELL_FLOOR))
            clamped += 1
    return clamped


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

    clamped = clamp_sells(priced)
    if clamped:
        print(f"(sell clamped to 95% of cheapest conversion-chain cost on {clamped} items)")

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
