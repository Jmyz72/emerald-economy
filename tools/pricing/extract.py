"""
Extract & normalize recipe + item-tag data from the vanilla + mod jars into
work/recipes.json and work/tags.json for the price deriver.

A normalized recipe = {
  "out": "<item id>", "count": <int>,
  "inputs": [{"id": "<item or #tag>", "count": <int>, "catalyst": <bool>}, ...],
  "type": "<recipe type>", "source": "<jar label>"
}
Only CONSTRUCTION recipe types are emitted (things that build an item up from
parts). Decomposition/processing-down types (cutting) are skipped so they can't
undervalue raw materials. crushing/milling ARE kept (they legitimately produce
modded processed items) but anchors win over them in the deriver.
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
    r = d.get("result")
    if r is None and "results" in d:
        rs = [x for x in d["results"] if "chance" not in x]  # main outputs only
        r = rs[0] if rs else d["results"][0]
    if isinstance(r, list):
        r = r[0]
    if isinstance(r, str):
        return r, 1
    if isinstance(r, dict):
        if "item" in r and isinstance(r["item"], dict):
            r = r["item"]
        return r.get("id") or r.get("item"), int(r.get("count", 1) or 1)
    return None, 1


def parse(d, source):
    t = d.get("type")
    if t not in CONSTRUCT:
        return None
    out, cnt = result_id_count(d)
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
        add(d.get("ingredient"), 1)
    elif t == "create:item_application":
        add(d.get("target"), 1)        # the converted block
        add(d.get("ingredient"), 1)    # the applied item
    elif t == "minecraft:smithing_transform":
        add(d.get("base"), 1)
        add(d.get("addition"), 1)
        if d.get("template"):
            add(d.get("template"), 1, catalyst=True)  # reusable, near-free
    elif t == "minecraft:crafting_transmute":
        add(d.get("input"), 1)
        add(d.get("material"), 1)
    elif t == "create:sequenced_assembly":
        add(d.get("ingredient"), 1)
        for step in d.get("sequence", []):
            if isinstance(step, dict) and step.get("type") == "create:deploying":
                if not step.get("keep_held_item"):
                    add(step.get("ingredient"), 1)
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
    return {"out": out, "count": cnt, "inputs": inputs, "type": t, "source": source}


def main():
    recipes, type_counts, skipped, untyped = [], {}, 0, 0
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
                    skipped += 1; continue
                r = parse(d, label)
                if r:
                    recipes.append(r)
                elif t in CONSTRUCT:
                    skipped += 1

    os.makedirs(os.path.join(here, "work"), exist_ok=True)
    with open(os.path.join(here, "work", "recipes.json"), "w") as f:
        json.dump(recipes, f)
    with open(os.path.join(here, "work", "tags.json"), "w") as f:
        json.dump(tags, f)

    print(f"recipes emitted: {len(recipes)}")
    print(f"item tags: {len(tags)}")
    print(f"untyped recipe files skipped: {untyped}")
    print("recipe type counts (all seen):")
    for t, c in sorted(type_counts.items(), key=lambda x: -x[1]):
        mark = "KEEP" if t in CONSTRUCT else ("skip" if t in SKIP else "----")
        print(f"   {mark}  {c:5}  {t}")


if __name__ == "__main__":
    main()
