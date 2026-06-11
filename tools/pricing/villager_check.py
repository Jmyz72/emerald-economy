"""Villager-trade arbitrage check (manual review aid, not part of derive).

Emeralds ARE the currency at face value 1, so any restocking villager trade that
sells N items for C emeralds where N x shop_sell > C is an infinite money printer
that arbitrage.py (crafting/conversion chains only) cannot see.
Trade list = vanilla 1.21 sell-to-player offers (master-level, best price-per-item).
Usage: python tools/pricing/villager_check.py
"""
import json, os

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
worth = json.load(open(os.path.join(ROOT, "worth.json")))
flat = {k: v for items in worth["categories"].values() for k, v in items.items()}

# (item, qty received, emerald cost)
TRADES = [
    # fletcher / librarian / cleric
    ("minecraft:arrow", 16, 1), ("minecraft:glass", 4, 1), ("minecraft:bookshelf", 1, 9),
    ("minecraft:lantern", 1, 1), ("minecraft:redstone", 2, 1), ("minecraft:lapis_lazuli", 1, 1),
    ("minecraft:glowstone", 1, 4), ("minecraft:ender_pearl", 1, 5),
    ("minecraft:experience_bottle", 1, 3),
    # farmer / butcher
    ("minecraft:bread", 6, 1), ("minecraft:pumpkin_pie", 4, 1), ("minecraft:apple", 4, 1),
    ("minecraft:cookie", 18, 3), ("minecraft:golden_carrot", 3, 3),
    ("minecraft:glistering_melon_slice", 3, 4), ("minecraft:cake", 1, 1),
    ("minecraft:cooked_porkchop", 5, 1), ("minecraft:cooked_chicken", 8, 1),
    ("minecraft:rabbit_stew", 1, 1),
    # mason
    ("minecraft:brick", 10, 1), ("minecraft:chiseled_stone_bricks", 4, 1),
    ("minecraft:polished_andesite", 4, 1), ("minecraft:polished_diorite", 4, 1),
    ("minecraft:polished_granite", 4, 1), ("minecraft:dripstone_block", 4, 1),
    ("minecraft:quartz_pillar", 1, 1), ("minecraft:quartz_block", 1, 1),
    ("minecraft:terracotta", 1, 1),
    # smiths / shepherd misc
    ("minecraft:shield", 1, 5), ("minecraft:bell", 1, 36),
    ("minecraft:flint_and_steel", 1, 7), ("minecraft:shears", 1, 2),
    ("minecraft:fishing_rod", 1, 6), ("minecraft:campfire", 1, 5),
    ("minecraft:saddle", 1, 6), ("minecraft:item_frame", 1, 7),
    # wandering trader (single-buy but cheap; restocks by new trader spawns)
    ("minecraft:red_sand", 4, 1), ("minecraft:packed_ice", 1, 3),
    ("minecraft:blue_ice", 1, 6), ("minecraft:podzol", 3, 3),
    ("minecraft:pointed_dripstone", 2, 1), ("minecraft:rooted_dirt", 2, 1),
    ("minecraft:moss_block", 2, 1), ("minecraft:slime_ball", 1, 4),
    ("minecraft:nautilus_shell", 1, 5), ("minecraft:sea_pickle", 1, 2),
    ("minecraft:gunpowder", 1, 1),
]

print(f"{'item':42} {'qty':>3} {'cost':>4} {'sell':>9} {'payout':>9}  verdict")
printers = []
for item, qty, cost in TRADES:
    v = flat.get(item)
    if not v or v.get("sell") is None:
        print(f"{item:42} {qty:3} {cost:4}   unlisted/no-sell (safe)")
        continue
    payout = v["sell"] * qty
    flag = "PRINTER" if payout > cost else ("tight" if payout > cost * 0.8 else "ok")
    if payout > cost:
        printers.append((item, payout / cost))
    print(f"{item:42} {qty:3} {cost:4} {v['sell']:9} {payout:9.1f}  {flag}")

print(f"\nmoney printers: {len(printers)}/{len(TRADES)}")
for item, x in sorted(printers, key=lambda t: -t[1]):
    print(f"   {x:8.1f}x  {item}")
