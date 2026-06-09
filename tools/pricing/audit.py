"""Flag likely-mispriced items per category for review.
A 'suspect' is: priced by the crude CATEGORY_DEFAULT fallback, OR a price outlier
within its category (buy in the top/bottom 5%), OR sell==buy*0.95 cap hits."""
import json, sys, statistics
sys.path.insert(0, "tools/pricing")
import importlib, anchors as A; importlib.reload(A)
import derive as D

worth, by_out, tags, cat_of = D.build()
dv = D.Deriver(by_out, tags, cat_of)

def source(k):
    if dv.via.get(k) != "anchor": return "recipe"
    if k in A.EXACT: return "exact"
    path = k.split(":",1)[1]
    for rx,_ in A.RULES:
        if rx.search(path): return "rule"
    return "DEFAULT"   # crude fallback

cat = sys.argv[1] if len(sys.argv)>1 else None
for c, items in worth["categories"].items():
    if cat and c != cat: continue
    priced = {k: D.round_buy(dv.price(k)) for k in items}
    for k in items: dv.price(k)  # ensure via populated
    defaults = [k for k in items if source(k)=="DEFAULT"]
    vals = sorted(v for v in priced.values() if v)
    hi = vals[int(len(vals)*0.95)] if vals else 0
    print(f"\n{'='*70}\n{c}  ({len(items)} items)  median buy {statistics.median(vals):.0f}  max {max(vals):.0f}")
    print(f"  fallback-priced (no specific anchor/recipe): {len(defaults)}")
    for k in sorted(defaults):
        print(f"     DEFAULT  {k:50} buy {priced[k]}")
