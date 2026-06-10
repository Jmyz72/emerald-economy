"""
Arbitrage audit: no chain of in-game conversions may turn shop-bought items into
a larger shop sell value. Reads the SHIPPED prices (worth.json) plus
work/conversions.json (EVERY conversion extract.py saw: construction recipes AND
the decomposition/processing types the pricer deliberately ignores - cutting,
splashing, crushing chance-byproducts, ...).

Two passes:
  1. acquisition cost A(x) = cheapest way to obtain x starting from the shop:
     buy it directly, or buy the inputs of any conversion chain (expected value
     for chance outputs, with sell-credit for the other outputs).
  2. flag every conversion whose expected sell revenue exceeds the acquisition
     cost of its inputs (beyond rounding noise).

This is the invariant the hand-maintained CELL pins in tiers.py exist to uphold;
run it after every derive.py --write. Exit code 1 if arbitrage is found.

  python tools/pricing/arbitrage.py            # report
  python tools/pricing/arbitrage.py --limit 80 # show more rows
"""
import json, math, os, sys

HERE = os.path.dirname(__file__)
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
INF = math.inf

# ignore sub-rounding noise: flag only profit > max(EPS_ABS, cost * EPS_REL)
EPS_ABS, EPS_REL = 0.05, 0.005

# Emeralds ARE the currency: convertible 1:1 by deposit/withdraw, so they have a
# hard buy and sell value even though the shop never lists them.
CURRENCY = {"minecraft:emerald": 1.0, "minecraft:emerald_block": 9.0}


def load():
    worth = json.load(open(os.path.join(ROOT, "worth.json")))
    conv = json.load(open(os.path.join(HERE, "work", "conversions.json")))
    tags = json.load(open(os.path.join(HERE, "work", "tags.json")))
    buy, sell = dict(CURRENCY), dict(CURRENCY)
    for items in worth["categories"].values():
        for k, v in items.items():
            if isinstance(v, dict):
                if v.get("buy") is not None:
                    buy[k] = float(v["buy"])
                if v.get("sell") is not None:
                    sell[k] = float(v["sell"])
    return buy, sell, conv, tags


def resolve(ing, tags, table, _seen=None):
    """Cheapest table value over the concrete ids an ingredient spec can be."""
    seen = _seen if _seen is not None else set()
    if isinstance(ing, dict) and "choices" in ing:
        vals = [resolve(c, tags, table, seen) for c in ing["choices"]]
        return min(vals) if vals else INF
    if isinstance(ing, str) and ing.startswith("#"):
        if ing in seen:
            return INF
        seen.add(ing)
        vals = [resolve(m, tags, table, seen) for m in tags.get(ing, [])]
        return min(vals) if vals else INF
    if isinstance(ing, str):
        return table.get(ing, INF)
    return INF


def input_cost(c, tags, A):
    cost = 0.0
    for i in c["in"]:
        a = resolve(i["id"], tags, A)
        if not math.isfinite(a):
            return INF
        cost += a * i.get("count", 1)
    return cost


def ev(o):
    return o.get("count", 1) * min(o.get("chance", 1.0), 1.0)


def acquisition(buy, conv, tags):
    """Fixed point: A(x) = min(buy x, cheapest conversion chain producing x).
    The full input cost is attributed to EACH output independently (no byproduct
    sell-credit): with credit, any genuine arbitrage cycle drives its members'
    A to zero and floods the report with cascade noise. The flag pass still sums
    full expected revenue, so byproduct-driven arbitrage is not missed there."""
    A = dict(buy)
    for _ in range(40):
        changed = False
        for c in conv:
            cost = input_cost(c, tags, A)
            if not math.isfinite(cost):
                continue
            for o in c["out"]:
                n = ev(o)
                if n <= 0:
                    continue
                cand = cost / n
                if cand < A.get(o["id"], INF) - 1e-9:
                    A[o["id"]] = cand
                    changed = True
        if not changed:
            break
    return A


def main():
    limit = 40
    if "--limit" in sys.argv:
        limit = int(sys.argv[sys.argv.index("--limit") + 1])
    buy, sell, conv, tags = load()
    A = acquisition(buy, conv, tags)

    flags = []
    for c in conv:
        cost = input_cost(c, tags, A)
        if not math.isfinite(cost):
            continue   # some input is unobtainable from the shop -> no entry point
        rev = sum(sell.get(o["id"], 0.0) * ev(o) for o in c["out"])
        if rev > cost + max(EPS_ABS, cost * EPS_REL):
            flags.append((rev - cost, cost, rev, c))

    flags.sort(key=lambda x: -x[0])
    for p, cost, rev, c in flags[:limit]:
        ins = " + ".join(f"{i.get('count', 1)}x {i['id']}" for i in c["in"])
        outs = " + ".join(f"{ev(o):g}x {o['id']}" for o in c["out"])
        print(f"ARB +{p:10.1f}  [{c['type']}]  {ins}  ->  {outs}"
              f"   (cost {cost:.1f}, sells {rev:.1f})")
    if len(flags) > limit:
        print(f"... and {len(flags) - limit} more (use --limit)")
    print(f"\n{len(flags)} arbitrage conversions / {len(conv)} checked")
    sys.exit(1 if flags else 0)


if __name__ == "__main__":
    main()
