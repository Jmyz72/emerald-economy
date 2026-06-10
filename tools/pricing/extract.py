"""
Extract & normalize recipe + item-tag data from the vanilla + mod jars into
work/recipes.json, work/conversions.json and work/tags.json.

A normalized recipe = {
  "out": "<item id>", "count": <int>, ["chance": <float 0-1>,]
  "inputs": [{"id": "<item or #tag>", "count": <int>, "catalyst": <bool>}, ...],
  "type": "<recipe type>", "source": "<jar label>"
}
recipes.json: only CONSTRUCTION recipe types (things that build an item up from
parts) - these feed the price deriver. Decomposition/processing-down types
(cutting) are skipped so they can't undervalue raw materials. crushing/milling
ARE kept (they legitimately produce modded processed items) but anchors win
over them in the deriver.

conversions.json: EVERY parseable item conversion - construction AND the
skipped decomposition types - with full output lists including chance
byproducts. The price deriver ignores it; arbitrage.py audits it, because a
conversion excluded from pricing still moves items in-game and must not turn
shop-bought inputs into a larger sell value.
"""
import zipfile, json, os, sys

MODS = r"C:\Users\carso\AppData\Roaming\.minecraft\mods"
JARS = {
    "vanilla": r"C:\Users\carso\AppData\Roaming\.minecraft\versions\fabric-loader-0.18.3-1.21.11\fabric-loader-0.18.3-1.21.11.jar",
    "fabricapi": os.path.join(MODS, "fabric-api-0.140.0+1.21.11.jar"),  # #c: conventional tags
    "create": os.path.join(MODS, "create-fly-1.21.11-6.0.8-3.jar"),
    "fd":     os.path.join(MODS, "FarmersDelight-1.21.11-3.4.2+refabricated.jar"),
    "bop":    os.path.join(MODS, "BiomesOPlenty-fabric-1.21.11-21.11.0.1.jar"),
    "rf":     os.path.join(MODS, "refurbished_furniture-fabric-1.21.11-1.0.21.jar"),
}

# Construction recipe types we trust to price an output up from its inputs.
CONSTRUCT = {
    "minecraft:crafting_shaped", "minecraft:crafting_shapeless",
    "minecraft:smelting", "minecraft:blasting", "minecraft:smoking",
    "minecraft:campfire_cooking", "minecraft:stonecutting",
    "create:mixing", "create:crushing", "create:milling",
    "create:deploying", "create:pressing", "create:compacting",
    "create:filling", "create:sequenced_assembly",
    "minecraft:smithing_transform", "minecraft:crafting_transmute",
    "create:item_application", "create:mechanical_crafting",
    "farmersdelight:cooking",
    "refurbished_furniture:workbench_constructing",
    "refurbished_furniture:oven_baking", "refurbished_furniture:frying_pan_cooking",
}
# Explicitly skipped (decomposition / break-down / catalysts-only).
SKIP = {
    "create:cutting", "farmersdelight:cutting",
    "refurbished_furniture:cutting_board_slicing",
    "refurbished_furniture:cutting_board_combining",
    "create:splashing", "create:haunting", "create:emptying",
    "create:sandpaper_polishing",
}


def norm_ingredient(v):
    """Return (id_or_tag, is_object) from the many ingredient encodings."""
    if v is None:
        return None
    if isinstance(v, str):
        return v
    if isinstance(v, list):
        # choice list -> mark as a synthetic tag we resolve later (cheapest member)
        members = [norm_ingredient(x) for x in v]
        members = [m for m in members if m]
        return {"choices": members} if members else None
    if isinstance(v, dict):
        for k in ("id", "item", "tag"):
            if k in v:
                val = v[k]
                if isinstance(val, dict):
                    return norm_ingredient(val)
                return ("#" + val) if k == "tag" else val
        if "ingredient" in v:
            return norm_ingredient(v["ingredient"])
        if "choices" in v:
            return v
    return None


def result_id_count(d):
    """(id, count, chance) of the MAIN output. Entries without a 'chance' key are
    certain. If EVERY output is chance-based (e.g. crushing Create's ore-stones),
    the first entry is the main output and its chance must carry into the price -
    treating a 40%-roll as a certain output is how crimsite-crushing underpriced
    crushed_raw_iron."""
    r = d.get("result")
    if r is None and "results" in d:
        rs = [x for x in d["results"] if not (isinstance(x, dict) and "chance" in x)]
        r = rs[0] if rs else d["results"][0]
    if isinstance(r, list):
        r = r[0]
    if isinstance(r, str):
        return r, 1, 1.0
    if isinstance(r, dict):
        chance = min(float(r.get("chance", 1.0) or 1.0), 1.0)
        if "item" in r and isinstance(r["item"], dict):
            r = r["item"]
        return r.get("id") or r.get("item"), int(r.get("count", 1) or 1), chance
    return None, 1, 1.0


def all_outputs(d):
    """[(id, count, chance)] for every result entry, byproducts included.
    Used for conversions.json so the arbitrage audit sees full expected yield."""
    rs = d.get("results")
    if rs is None:
        rs = d.get("result")
    if rs is None:
        return []
    if not isinstance(rs, list):
        rs = [rs]
    outs = []
    for e in rs:
        if isinstance(e, str):
            outs.append((e, 1, 1.0))
            continue
        if not isinstance(e, dict):
            continue
        ch = min(float(e.get("chance", 1.0) or 1.0), 1.0)
        it = e.get("item")
        if isinstance(it, dict):                 # {"item": {"id":..,"count":..}}
            e = dict(it)
        elif isinstance(it, str):                # {"item": "id", "count": n}
            outs.append((it, int(e.get("count", 1) or 1), ch))
            continue
        oid = e.get("id")
        if isinstance(oid, str):
            outs.append((oid, int(e.get("count", 1) or 1), ch))
    return outs


def parse(d, source):
    t = d.get("type")
    if t not in CONSTRUCT:
        return None
    out, cnt, chance = result_id_count(d)
    if not out:
        return None
    inputs = []

    def add(ing, c=1, catalyst=False):
        n = norm_ingredient(ing)
        if n:
            inputs.append({"id": n, "count": c, "catalyst": catalyst})

    if t in ("minecraft:crafting_shaped", "create:mechanical_crafting"):
        key = d.get("key", {})
        counts = {}
        for row in d.get("pattern", []):
            for ch in row:
                if ch != " ":
                    counts[ch] = counts.get(ch, 0) + 1
        for ch, c in counts.items():
            if ch in key:
                add(key[ch], c)
    elif t in ("minecraft:crafting_shapeless", "create:mixing", "create:compacting",
               "farmersdelight:cooking"):
        for ing in d.get("ingredients", []):
            add(ing, 1)
    elif t in ("minecraft:smelting", "minecraft:blasting", "minecraft:smoking",
               "minecraft:campfire_cooking", "minecraft:stonecutting",
               "create:crushing", "create:milling", "create:pressing"):
        add(d.get("ingredient"), 1)
    elif t == "create:deploying":
        # axe-deploying = scraping wax/oxidation off or stripping logs: decomposition,
        # not construction. Skip so it can't undervalue the plain form.
        if norm_ingredient(d.get("ingredient")) == "#minecraft:axes":
            return None
        add(d.get("target"), 1)
        if not d.get("keep_held_item"):
            add(d.get("ingredient"), 1)          # consumed held item
        else:
            add(d.get("ingredient"), 1, catalyst=True)
    elif t == "create:filling":
        # potion fluids are real brewing cost (2700mb ~ 11 bottles of night
        # vision/strength/...) that this model can't price; water/lava/honey
        # are renewable-cheap and priced at 0
        fl = d.get("fluid_ingredient") or {}
        if fl.get("fluid") == "create:potion":
            return None
        add(d.get("ingredient"), 1)
    elif t == "create:item_application":
        add(d.get("target"), 1)        # the converted block
        add(d.get("ingredient"), 1)    # the applied item
    elif t == "minecraft:smithing_transform":
        add(d.get("base"), 1)
        add(d.get("addition"), 1)
        if d.get("template"):
            add(d.get("template"), 1)  # CONSUMED on use (since 1.20), not a catalyst
    elif t == "minecraft:crafting_transmute":
        add(d.get("input"), 1)
        add(d.get("material"), 1)
    elif t == "create:sequenced_assembly":
        add(d.get("ingredient"), 1)
        # the sequence repeats `loops` times, so each consumed deploy item is
        # spent once PER LOOP (precision_mechanism: loops=5)
        loops = int(d.get("loops", 1) or 1)
        for step in d.get("sequence", []):
            if isinstance(step, dict) and step.get("type") == "create:deploying":
                if not step.get("keep_held_item"):
                    add(step.get("ingredient"), loops)
    elif t in ("refurbished_furniture:workbench_constructing",
               "refurbished_furniture:oven_baking",
               "refurbished_furniture:frying_pan_cooking"):
        for m in d.get("materials", []):
            add(m.get("ingredient"), int(m.get("count", 1) or 1))
        if "ingredient" in d:
            add(d.get("ingredient"), 1)
    else:
        return None

    if not inputs:
        return None
    r = {"out": out, "count": cnt, "inputs": inputs, "type": t, "source": source}
    if chance < 1.0:
        r["chance"] = chance   # expected cost per success = cost / count / chance
    return r


def parse_skip(d, source):
    """Conversions the pricer deliberately ignores (cutting, splashing, ...) still
    move items in-game, so the arbitrage audit needs them in conversions.json."""
    ins = []
    raw = d.get("ingredients")
    if raw is None and "ingredient" in d:
        raw = [d["ingredient"]]
    if raw is None and "input" in d:
        raw = [d["input"]]
    for ing in raw or []:
        n = norm_ingredient(ing)
        if n:
            ins.append({"id": n, "count": 1})
    outs = [{"id": i, "count": c, "chance": ch} for (i, c, ch) in all_outputs(d)]
    if not ins or not outs:
        return None
    return {"in": ins, "out": outs, "type": d["type"], "source": source}


def main():
    recipes, type_counts, skipped, untyped = [], {}, 0, 0
    conversions = []
    tags = {}
    here = os.path.dirname(__file__)
    for label, jp in JARS.items():
        if not os.path.exists(jp):
            print("MISSING jar:", label, jp); sys.exit(1)
        z = zipfile.ZipFile(jp)
        for n in z.namelist():
            if not n.endswith(".json"):
                continue
            if "/tags/item" in n:
                try:
                    d = json.loads(z.read(n))
                except Exception:
                    continue
                # data/<ns>/tags/item/<path>.json -> #<ns>:<path>
                # Fabric override path data/fabric/<ns>/tags/item/... contributes to <ns>.
                parts = n.split("/tags/item/")
                segs = parts[0].split("/")          # ["data", ns, ...] or ["data","fabric",ns]
                ns = segs[2] if len(segs) >= 3 and segs[1] == "fabric" else segs[1]
                name = parts[1][:-5]
                key = f"#{ns}:{name}"
                vals = []
                for e in d.get("values", []):
                    if isinstance(e, str):
                        vals.append(e)
                    elif isinstance(e, dict) and "id" in e:
                        vals.append(e["id"])
                tags.setdefault(key, [])
                tags[key].extend(vals)
                continue
            if "/recipe/" in n or "/recipes/" in n:
                try:
                    d = json.loads(z.read(n))
                except Exception:
                    continue
                t = d.get("type")
                if not t:
                    untyped += 1; continue
                type_counts[t] = type_counts.get(t, 0) + 1
                if t in SKIP:
                    skipped += 1
                    cv = parse_skip(d, label)
                    if cv:
                        conversions.append(cv)
                    continue
                r = parse(d, label)
                if r:
                    recipes.append(r)
                    outs = all_outputs(d)   # full yield incl. chance byproducts
                    if not outs:
                        outs = [(r["out"], r["count"], r.get("chance", 1.0))]
                    conversions.append({
                        "in": [i for i in r["inputs"] if not i.get("catalyst")],
                        "out": [{"id": i, "count": c, "chance": ch}
                                for (i, c, ch) in outs],
                        "type": t, "source": label,
                    })
                elif t in CONSTRUCT:
                    skipped += 1

    os.makedirs(os.path.join(here, "work"), exist_ok=True)
    with open(os.path.join(here, "work", "recipes.json"), "w") as f:
        json.dump(recipes, f)
    with open(os.path.join(here, "work", "tags.json"), "w") as f:
        json.dump(tags, f)
    with open(os.path.join(here, "work", "conversions.json"), "w") as f:
        json.dump(conversions, f)

    print(f"recipes emitted: {len(recipes)}")
    print(f"conversions emitted (for arbitrage audit): {len(conversions)}")
    print(f"item tags: {len(tags)}")
    print(f"untyped recipe files skipped: {untyped}")
    print("recipe type counts (all seen):")
    for t, c in sorted(type_counts.items(), key=lambda x: -x[1]):
        mark = "KEEP" if t in CONSTRUCT else ("skip" if t in SKIP else "----")
        print(f"   {mark}  {c:5}  {t}")


if __name__ == "__main__":
    main()
