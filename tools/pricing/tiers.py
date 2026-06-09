"""
tiers.py — root-item pricing for the price deriver. Replaces anchors.py.

A ROOT item (one that no construction recipe produces) is priced by:

    root_buy = band_base × effort_factor × rarity_factor

Classification of an item into a (band, effort, rarity) cell is resolved in layers
(first hit wins):

  1. EXACT[id]          -> a final price, bypassing the formula AND winning over recipes
                           (currency + true one-offs above the formula ceiling)
  2. CELL[id]           -> explicit (band, effort, rarity) per-item override
  3. RULES              -> first matching regex on the id path -> (band, effort, rarity)
  4. CATEGORY_DEFAULT   -> per-creative-tab fallback so nothing is unclassified

Sell is a rarity-scaled fraction of buy. Crafted items do NOT use this module — their
buy/sell are derived from the recipe graph in derive.py.
"""
import re

# ---------------------------------------------------------------- ladders (global)
EFFORT = {1: 1, 2: 3, 3: 8, 4: 20, 5: 40}
RARITY = {1: 1, 2: 2, 3: 5, 4: 12, 5: 40}

# ---------------------------------------------------------------- band base prices
BAND_BASE = {
    "raw_stone": 5,
    "raw_metal": 5,
    "raw_gem": 5,
    "mob_drop": 6,
    "plant_produce": 4,
    "natural_block": 6,
    "treasure": 12,
    "default": 5,
}

# ---------------------------------------------------------------- sell (rarity-scaled)
SELL_FRACTION = {1: 0.25, 2: 0.30, 3: 0.40, 4: 0.55, 5: 0.70}
SELL_FLOOR = 0.01

# Currency-equivalent items: priced/sold at face value, bypassing the formula.
CURRENCY_FACE = {"minecraft:emerald_block": 9}

# ---------------------------------------------------------------- EXACT (final price)
# Wins over both the formula and any recipe. Currency + genuine one-offs that sit above
# the ~8000 formula ceiling or carry special meaning. Keep this SHORT.
EXACT = {
    "minecraft:emerald": 1,
    "minecraft:emerald_block": 9,
    "minecraft:nether_star": 700000,
    "minecraft:dragon_egg": 1000000,
    "minecraft:dragon_head": 50000,
    "minecraft:elytra": 50000,
    "minecraft:totem_of_undying": 15000,
    "minecraft:enchanted_golden_apple": 80000,
    "minecraft:heart_of_the_sea": 12000,
    "minecraft:heavy_core": 20000,
    "minecraft:sniffer_egg": 8000,
    "minecraft:netherite_upgrade_smithing_template": 120000,
    "minecraft:silence_armor_trim_smithing_template": 150000,
    "minecraft:ward_armor_trim_smithing_template": 120000,
    "minecraft:flow_armor_trim_smithing_template": 100000,
    "minecraft:bolt_armor_trim_smithing_template": 100000,
}

# ---------------------------------------------------------------- per-item cell overrides
# (band, effort, rarity). Anchors the key roots that calibration is pinned to, plus the
# raw materials whose default cell would otherwise be wrong.
CELL = {
    # raw stone / earth — trivial + abundant
    "minecraft:cobblestone": ("raw_stone", 1, 1),
    "minecraft:dirt": ("raw_stone", 1, 1),
    "minecraft:sand": ("raw_stone", 1, 1),
    "minecraft:red_sand": ("raw_stone", 1, 1),
    "minecraft:gravel": ("raw_stone", 1, 1),
    "minecraft:netherrack": ("raw_stone", 1, 1),
    "minecraft:stone": ("raw_stone", 1, 2),
    "minecraft:deepslate": ("raw_stone", 1, 2),
    "minecraft:cobbled_deepslate": ("raw_stone", 1, 1),
    "minecraft:obsidian": ("raw_stone", 3, 3),
    # raw metals / ores
    "minecraft:coal": ("raw_metal", 2, 2),
    "minecraft:charcoal": ("raw_metal", 2, 2),
    "minecraft:raw_copper": ("raw_metal", 3, 3),
    "minecraft:copper_ingot": ("raw_metal", 3, 3),
    "minecraft:raw_iron": ("raw_metal", 3, 4),
    "minecraft:raw_gold": ("raw_metal", 3, 4),
    "minecraft:redstone": ("raw_metal", 2, 3),
    "minecraft:lapis_lazuli": ("raw_metal", 2, 3),
    "minecraft:quartz": ("raw_metal", 3, 3),
    "minecraft:glowstone_dust": ("raw_metal", 3, 3),
    # raw gems
    "minecraft:diamond": ("raw_gem", 4, 5),
    "minecraft:amethyst_shard": ("raw_gem", 2, 3),
    "minecraft:ancient_debris": ("raw_gem", 4, 5),
    "minecraft:netherite_scrap": ("raw_gem", 4, 5),
    # mob drops
    "minecraft:rotten_flesh": ("mob_drop", 1, 1),
    "minecraft:bone": ("mob_drop", 2, 1),
    "minecraft:string": ("mob_drop", 2, 1),
    "minecraft:gunpowder": ("mob_drop", 2, 3),
    "minecraft:leather": ("mob_drop", 2, 3),
    "minecraft:ender_pearl": ("mob_drop", 4, 4),
    "minecraft:blaze_rod": ("mob_drop", 4, 3),
    "minecraft:ghast_tear": ("mob_drop", 4, 4),
    "minecraft:shulker_shell": ("mob_drop", 5, 3),
    "minecraft:nether_star": ("treasure", 5, 5),  # also in EXACT; cell kept for audit display
    # plants
    "minecraft:wheat": ("plant_produce", 2, 2),
    "minecraft:sugar_cane": ("plant_produce", 1, 2),
    "minecraft:bamboo": ("plant_produce", 1, 1),
}

# ---------------------------------------------------------------- regex rules (ordered)
# Compiled against the path after the namespace. First match wins. Each maps a family of
# ids to a (band, effort, rarity) cell.
def _suf(*sfx):
    return re.compile("(" + "|".join(re.escape(s) for s in sfx) + ")$")

RULES = [
    (_suf("_log", "_stem", "_hyphae", "_wood"), ("plant_produce", 2, 3)),
    (re.compile(r"^stripped_.*(_log|_stem|_wood|_hyphae)$"), ("plant_produce", 2, 3)),
    (_suf("_leaves"), ("plant_produce", 1, 1)),
    (_suf("_sapling", "_propagule"), ("plant_produce", 1, 3)),
    (re.compile(r"_ore$"), ("raw_metal", 3, 3)),
    (re.compile(r"(raw_).*"), ("raw_metal", 3, 3)),
    (re.compile(r"_ingot$|_gem$|_crystal$"), ("raw_metal", 3, 3)),
    (re.compile(r"_concrete$|_concrete_powder$"), ("raw_stone", 2, 2)),
    (re.compile(r"_sand$"), ("raw_stone", 1, 1)),
    (re.compile(r"(creeper|zombie|skeleton|piglin|player)_head$|skeleton_skull$"),
        ("mob_drop", 3, 3)),
    (re.compile(r"coral(_block|_fan|_wall_fan)?$"), ("natural_block", 2, 2)),
    (re.compile(r"(tulip|orchid|allium|cornflower|poppy|dandelion|daisy|"
                r"lily_of_the_valley|rose_bush|sunflower|pink_petals)$"),
        ("plant_produce", 1, 1)),
    (re.compile(r"(short_grass|tall_grass|fern|dead_bush|seagrass|vine|kelp)$"),
        ("plant_produce", 1, 1)),
    (re.compile(r"_mushroom$|mushroom_block$|mushroom_stem$"), ("plant_produce", 2, 2)),
]

# ---------------------------------------------------------------- per-category defaults
# Safety net so nothing is unclassified. Most items in these tabs are crafted (priced by
# recipe), so this only bites on roots that no CELL/RULE matched.
CATEGORY_DEFAULT = {
    "building_blocks": ("raw_stone", 2, 2),
    "colored_blocks": ("raw_stone", 2, 2),
    "natural_blocks": ("natural_block", 1, 2),
    "functional_blocks": ("default", 3, 3),
    "redstone_blocks": ("raw_metal", 2, 3),
    "tools_and_utilities": ("default", 3, 4),
    "combat": ("default", 3, 4),
    "food_and_drinks": ("plant_produce", 2, 2),
    "ingredients": ("mob_drop", 2, 3),
    "create.base": ("default", 3, 3),
    "create.palettes": ("raw_stone", 2, 2),
    "farmersdelight.farmersdelight": ("plant_produce", 2, 2),
    "refurbished_furniture.creative_tab": ("default", 3, 3),
    "biomesoplenty.main": ("natural_block", 1, 2),
    "uncategorized": ("default", 2, 3),
}

DEFAULT_CELL = ("default", 2, 2)


# ---------------------------------------------------------------- public API
def classify(item_id, category):
    """Resolve an item to a (band, effort, rarity) cell via the layered rules."""
    if item_id in CELL:
        return CELL[item_id]
    path = item_id.split(":", 1)[1] if ":" in item_id else item_id
    for rx, cell in RULES:
        if rx.search(path):
            return cell
    return CATEGORY_DEFAULT.get(category, DEFAULT_CELL)


def formula_price(cell):
    """band_base × effort_factor × rarity_factor for a (band, effort, rarity) cell."""
    band, effort, rarity = cell
    return BAND_BASE.get(band, BAND_BASE["default"]) * EFFORT[effort] * RARITY[rarity]


def root_buy(item_id, category):
    """Buy price for a ROOT item: EXACT override if present, else the formula."""
    if item_id in EXACT:
        return float(EXACT[item_id])
    return float(formula_price(classify(item_id, category)))


def root_sell(item_id, buy, category):
    """Sell price for a ROOT item: rarity-scaled fraction of buy. Currency = face value."""
    if item_id in CURRENCY_FACE:
        return float(CURRENCY_FACE[item_id])
    if not buy or buy <= 0:
        return SELL_FLOOR
    _, _, rarity = classify(item_id, category)
    return buy * SELL_FRACTION[rarity]
