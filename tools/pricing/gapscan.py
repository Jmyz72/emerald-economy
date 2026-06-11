"""One-shot manual-review gap scan over worth.json.

Checks (independent of derive.py's model):
  1. duplicate ids across categories (flatten() keeps one silently)
  2. entries with both buy and sell null (dead whitelist entries)
  3. zero / negative prices
  4. sell >= buy (instant arbitrage at the shop counter itself)
  5. sell/buy ratio outliers (> 0.6 — suspiciously generous payout)
  6. buy-only / sell-only counts per category (asymmetric listings)
  7. recipe outputs (work/recipes.json) absent from worth.json entirely
  8. conversion outputs absent from worth.json
  9. emerald / currency item accidentally listed
Usage: python tools/pricing/gapscan.py
"""
import json, collections, os

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
worth = json.load(open(os.path.join(ROOT, "worth.json")))
WORK = os.path.join(ROOT, "tools", "pricing", "work")
recipes = json.load(open(os.path.join(WORK, "recipes.json")))
conversions = json.load(open(os.path.join(WORK, "conversions.json")))

cats = worth["categories"]
seen = {}
dupes = []
flat = {}
for c, items in cats.items():
    for k, v in items.items():
        if k in seen:
            dupes.append((k, seen[k], c))
        seen[k] = c
        flat[k] = v

print(f"total entries: {sum(len(i) for i in cats.values())}  unique ids: {len(flat)}")

print(f"\n== 1. duplicate ids across categories: {len(dupes)}")
for k, a, b in dupes[:40]:
    pa, pb = None, None
    print(f"   {k}: in '{a}' and '{b}'  ({cats[a].get(k) if k in cats[a] else ''} vs {cats[b][k]})")

nullnull = [k for k, v in flat.items() if v.get("buy") is None and v.get("sell") is None]
print(f"\n== 2. null/null (listed but untradeable): {len(nullnull)}")
for k in nullnull[:60]:
    print(f"   {k}")

bad = [(k, v) for k, v in flat.items()
       if (v.get("buy") is not None and v["buy"] <= 0) or (v.get("sell") is not None and v["sell"] <= 0)]
print(f"\n== 3. zero/negative prices: {len(bad)}")
for k, v in bad[:40]:
    print(f"   {k}: {v}")

selge = [(k, v) for k, v in flat.items()
         if v.get("buy") is not None and v.get("sell") is not None and v["sell"] >= v["buy"]]
print(f"\n== 4. sell >= buy: {len(selge)}")
for k, v in selge[:40]:
    print(f"   {k}: buy {v['buy']} sell {v['sell']}")

ratios = [(v["sell"] / v["buy"], k, v) for k, v in flat.items()
          if v.get("buy") and v.get("sell") and v["buy"] > 0]
hi = sorted([r for r in ratios if r[0] > 0.6], reverse=True)
print(f"\n== 5. sell/buy ratio > 0.6: {len(hi)}")
for r, k, v in hi[:40]:
    print(f"   {r:.2f}  {k}: buy {v['buy']} sell {v['sell']}")

print("\n== 6. per-category buy-only / sell-only / both / neither")
for c, items in cats.items():
    b = s = both = neither = 0
    for v in items.values():
        hb, hs = v.get("buy") is not None, v.get("sell") is not None
        if hb and hs: both += 1
        elif hb: b += 1
        elif hs: s += 1
        else: neither += 1
    flag = "  <-- check" if (b or s or neither) else ""
    print(f"   {c:45} both {both:4}  buy-only {b:3}  sell-only {s:3}  null/null {neither:3}{flag}")

def outputs_of(recs):
    outs = set()
    for r in recs:
        o = r.get("output") or r.get("result") or r.get("out")
        if isinstance(o, str):
            outs.add(o)
        elif isinstance(o, dict):
            oid = o.get("id") or o.get("item")
            if oid: outs.add(oid)
        elif isinstance(o, list):
            for x in o:
                if isinstance(x, str): outs.add(x)
                elif isinstance(x, dict):
                    oid = x.get("id") or x.get("item")
                    if oid: outs.add(oid)
    return outs

def collect(obj):
    """recipes.json shape unknown — handle list or dict of lists."""
    if isinstance(obj, list):
        return outputs_of(obj)
    outs = set()
    if isinstance(obj, dict):
        for v in obj.values():
            outs |= collect(v)
    return outs

rec_outs = collect(recipes)
conv_outs = collect(conversions)
missing_r = sorted(o for o in rec_outs if o not in flat and not o.startswith("minecraft:emerald"))
missing_c = sorted(o for o in conv_outs if o not in flat and not o.startswith("minecraft:emerald"))
print(f"\n== 7. craftable (recipe output) but NOT in worth.json: {len(missing_r)}")
for k in missing_r[:80]:
    print(f"   {k}")
print(f"\n== 8. conversion output but NOT in worth.json: {len(missing_c)}")
for k in missing_c[:80]:
    print(f"   {k}")

print(f"\n== 9. currency listed? {'YES: ' + str(flat.get('minecraft:emerald')) if 'minecraft:emerald' in flat else 'no (good)'}")
for cur in ("minecraft:emerald_block", "minecraft:emerald_ore", "minecraft:deepslate_emerald_ore"):
    if cur in flat:
        print(f"   note: {cur}: {flat[cur]}")
