"""Per-category QA for the effort×rarity pricer.
For each category, list how each root resolved (exact / cell / rule / category-default)
and its (band, effort, rarity) cell + computed buy. Category-default roots are the
worklist: they fell through to the crude per-tab fallback and likely want a CELL or RULE.
Usage:  python tools/pricing/audit.py [category]
"""
import sys, statistics
sys.path.insert(0, "tools/pricing")
import importlib, tiers as T; importlib.reload(T)
import derive as D

worth, by_out, tags, cat_of = D.build()
dv = D.Deriver(by_out, tags, cat_of)


def source(k):
    """How was item k's price decided?"""
    if dv.via.get(k) != "anchor":
        return "recipe"
    if k in T.EXACT:
        return "exact"
    if k in T.CELL:
        return "cell"
    path = k.split(":", 1)[1] if ":" in k else k
    for rx, _ in T.RULES:
        if rx.search(path):
            return "rule"
    return "DEFAULT"   # fell through to per-category fallback — needs a rule


only = sys.argv[1] if len(sys.argv) > 1 else None
for c, items in worth["categories"].items():
    if only and c != only:
        continue
    priced = {k: D.round_buy(dv.price(k)) for k in items}  # also populates dv.via
    vals = sorted(v for v in priced.values() if v)
    if not vals:
        continue
    defaults = [k for k in items if source(k) == "DEFAULT"]
    print(f"\n{'='*72}\n{c}  ({len(items)} items)  "
          f"median buy {statistics.median(vals):.0f}  max {max(vals):.0f}")
    print(f"  category-default roots (need a CELL/RULE): {len(defaults)}")
    for k in sorted(defaults):
        band, e, r = T.classify(k, c)
        print(f"     DEFAULT  {k:48} buy {str(priced[k]):>8}   [{band} e{e} r{r}]")
