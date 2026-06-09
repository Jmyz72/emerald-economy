"""
Anchor prices (buy, in emeralds) for ROOT items — those no construction recipe
produces — plus farm-tier classification used to compute sell prices.

Resolution order for a root's buy price:
  1. EXACT[id]            explicit per-item override
  2. first matching RULE  (ordered; suffix / predicate -> price)
  3. CATEGORY_DEFAULT     per-category fallback so nothing is unpriced

Anchors also WIN over derived values in the deriver: an item listed in EXACT
keeps that price even if a recipe could "make" it cheaper. This stops
decomposition/processing recipes from undervaluing raw materials.
"""
import re

# ---------------------------------------------------------------- headline anchors
# The design's reference points + key raw materials. These are pinned exactly.
EXACT = {
    # base metals / minerals (T2-T4)
    "minecraft:raw_iron": 400, "minecraft:iron_ingot": 400, "minecraft:iron_nugget": 45,
    "minecraft:raw_copper": 200, "minecraft:copper_ingot": 200,
    "minecraft:raw_gold": 500, "minecraft:gold_ingot": 500, "minecraft:gold_nugget": 56,
    "minecraft:redstone": 60, "minecraft:lapis_lazuli": 60, "minecraft:coal": 40,
    "minecraft:charcoal": 40, "minecraft:quartz": 200, "minecraft:amethyst_shard": 80,
    "minecraft:diamond": 4000, "minecraft:emerald": 1,  # currency face value (so emerald_block=9)
    "minecraft:emerald_block": 9,                        # bundled currency: priced at face
    "minecraft:netherite_scrap": 4000, "minecraft:netherite_ingot": 20000,
    "minecraft:glowstone_dust": 75, "minecraft:glowstone": 300,
    # Create base metals (anchored so storage-block decompose recipes can't loop them cheap)
    "create:zinc_ingot": 200, "create:zinc_nugget": 22, "create:raw_zinc": 200,
    "create:brass_ingot": 400, "create:brass_nugget": 44,
    "create:rose_quartz": 300, "create:experience_nugget": 30,
    "create:refined_radiance_casing": 1200, "create:shadow_steel_casing": 1200,  # endgame
    "create:refined_radiance": 1000, "create:shadow_steel": 1000,
    "autotrader:auto_trader": 5000,  # automation block, notable utility
    # ores (block, mined) ~ their yield
    "minecraft:coal_ore": 40, "minecraft:deepslate_coal_ore": 40,
    "minecraft:iron_ore": 400, "minecraft:deepslate_iron_ore": 400,
    "minecraft:copper_ore": 200, "minecraft:deepslate_copper_ore": 200,
    "minecraft:gold_ore": 500, "minecraft:deepslate_gold_ore": 500, "minecraft:nether_gold_ore": 400,
    "minecraft:diamond_ore": 4000, "minecraft:deepslate_diamond_ore": 4000,
    "minecraft:redstone_ore": 120, "minecraft:deepslate_redstone_ore": 120,
    "minecraft:lapis_ore": 120, "minecraft:deepslate_lapis_ore": 120,
    "minecraft:emerald_ore": 50, "minecraft:deepslate_emerald_ore": 50,
    "minecraft:nether_quartz_ore": 200, "minecraft:ancient_debris": 4000,
    # primitive stone / earth (anchored so milling/crushing recipes can't misprice)
    "minecraft:cobblestone": 5, "minecraft:stone": 8, "minecraft:cobbled_deepslate": 5,
    "minecraft:deepslate": 6, "minecraft:granite": 6, "minecraft:diorite": 6,
    "minecraft:andesite": 6, "minecraft:tuff": 6, "minecraft:calcite": 8,
    "minecraft:sand": 4, "minecraft:red_sand": 5, "minecraft:gravel": 5,
    "minecraft:clay": 14, "minecraft:clay_ball": 3, "minecraft:flint": 5,
    "minecraft:snow_block": 6, "minecraft:snow": 2, "minecraft:ice": 8,
    "minecraft:packed_ice": 24, "minecraft:blue_ice": 64, "minecraft:dripstone_block": 12,
    "minecraft:magma_block": 30, "minecraft:smooth_basalt": 8, "minecraft:tuff_bricks": 8,
    # earth / stone roots
    "minecraft:dirt": 5, "minecraft:coarse_dirt": 5, "minecraft:rooted_dirt": 8,
    "minecraft:netherrack": 5, "minecraft:end_stone": 8, "minecraft:basalt": 8,
    "minecraft:blackstone": 8, "minecraft:gilded_blackstone": 200,
    "minecraft:cobbled_deepslate": 5, "minecraft:obsidian": 400, "minecraft:crying_obsidian": 600,
    "minecraft:soul_sand": 15, "minecraft:soul_soil": 15, "minecraft:podzol": 10,
    "minecraft:mycelium": 15, "minecraft:moss_block": 15, "minecraft:pale_moss_block": 15,
    "minecraft:pointed_dripstone": 20, "minecraft:cobweb": 40, "minecraft:wet_sponge": 200,
    "minecraft:carved_pumpkin": 30, "minecraft:suspicious_sand": 50, "minecraft:suspicious_gravel": 50,
    # sculk family
    "minecraft:sculk": 20, "minecraft:sculk_vein": 20, "minecraft:sculk_sensor": 300,
    "minecraft:sculk_shrieker": 300, "minecraft:sculk_catalyst": 400,
    # froglights
    "minecraft:ochre_froglight": 150, "minecraft:pearlescent_froglight": 150,
    "minecraft:verdant_froglight": 150,
    # mob drops
    "minecraft:rotten_flesh": 5, "minecraft:bone": 20, "minecraft:bone_meal": 7,
    "minecraft:string": 15, "minecraft:spider_eye": 15, "minecraft:gunpowder": 40,
    "minecraft:feather": 10, "minecraft:leather": 60, "minecraft:rabbit_hide": 30,
    "minecraft:ink_sac": 15, "minecraft:glow_ink_sac": 40, "minecraft:slime_ball": 30,
    "minecraft:honeycomb": 30, "minecraft:phantom_membrane": 150, "minecraft:rabbit_foot": 200,
    "minecraft:ender_pearl": 1200, "minecraft:blaze_rod": 800, "minecraft:breeze_rod": 800,
    "minecraft:ghast_tear": 600, "minecraft:magma_cream": 100, "minecraft:dragon_breath": 300,
    "minecraft:prismarine_shard": 80, "minecraft:prismarine_crystals": 150,
    "minecraft:nautilus_shell": 400, "minecraft:shulker_shell": 800, "minecraft:echo_shard": 600,
    "minecraft:armadillo_scute": 60, "minecraft:turtle_scute": 100, "minecraft:goat_horn": 800,
    "minecraft:snowball": 2, "minecraft:egg": 5, "minecraft:blue_egg": 5, "minecraft:brown_egg": 5,
    # raw food
    "minecraft:beef": 25, "minecraft:chicken": 25, "minecraft:porkchop": 25, "minecraft:mutton": 25,
    "minecraft:rabbit": 25, "minecraft:cod": 20, "minecraft:salmon": 22, "minecraft:tropical_fish": 30,
    "minecraft:pufferfish": 30, "minecraft:melon_slice": 5, "minecraft:sweet_berries": 8,
    "minecraft:glow_berries": 10, "minecraft:chorus_fruit": 15, "minecraft:apple": 10,
    "minecraft:cocoa_beans": 15, "minecraft:pitcher_pod": 20, "minecraft:torchflower_seeds": 20,
    "minecraft:poisonous_potato": 5, "minecraft:nether_wart": 20,
    # plant produce / farmables
    "minecraft:wheat": 40, "minecraft:sugar_cane": 12, "minecraft:bamboo": 5, "minecraft:kelp": 5,
    "minecraft:cactus": 8, "minecraft:carrot": 12, "minecraft:potato": 12, "minecraft:beetroot": 12,
    "minecraft:beetroot_seeds": 6, "minecraft:wheat_seeds": 4, "minecraft:melon": 30, "minecraft:pumpkin": 25,
    # buckets / liquids
    "minecraft:water_bucket": 50, "minecraft:lava_bucket": 100, "minecraft:powder_snow_bucket": 60,
    "minecraft:milk_bucket": 60, "minecraft:axolotl_bucket": 200, "minecraft:tadpole_bucket": 100,
    "minecraft:cod_bucket": 60, "minecraft:salmon_bucket": 62, "minecraft:pufferfish_bucket": 70,
    "minecraft:tropical_fish_bucket": 70,
    # trophies / uniques (T5-T6)
    "minecraft:nether_star": 700000, "minecraft:dragon_egg": 1000000,
    "minecraft:heart_of_the_sea": 12000, "minecraft:totem_of_undying": 15000,
    "minecraft:enchanted_golden_apple": 80000, "minecraft:elytra": 50000,
    "minecraft:trident": 8000, "minecraft:heavy_core": 20000, "minecraft:dragon_head": 50000,
    "minecraft:sniffer_egg": 8000, "minecraft:turtle_egg": 200, "minecraft:frogspawn": 50,
    "minecraft:chipped_anvil": 12000, "minecraft:damaged_anvil": 11000,  # worn anvils
    "minecraft:bee_nest": 150, "minecraft:pitcher_plant": 40,
    "minecraft:copper_golem_statue": 400,  # decorative, no recipe (root)
    "minecraft:bell": 2000, "minecraft:wither_skeleton_skull": 2000,
    "minecraft:enchanted_book": 2000, "minecraft:written_book": 50, "minecraft:filled_map": 50,
    "minecraft:experience_bottle": 200, "minecraft:ominous_bottle": 1000,
    "minecraft:name_tag": 600, "minecraft:saddle": 800, "minecraft:resin_block": 90,
    "minecraft:trial_key": 2000, "minecraft:ominous_trial_key": 5000,
    "minecraft:disc_fragment_5": 600,
    # chainmail (mob-only) & horse armor
    "minecraft:chainmail_helmet": 500, "minecraft:chainmail_chestplate": 800,
    "minecraft:chainmail_leggings": 700, "minecraft:chainmail_boots": 400,
    "minecraft:iron_horse_armor": 500, "minecraft:golden_horse_armor": 800,
    "minecraft:diamond_horse_armor": 5000, "minecraft:copper_horse_armor": 300,
    "minecraft:iron_nautilus_armor": 500, "minecraft:golden_nautilus_armor": 800,
    "minecraft:diamond_nautilus_armor": 5000, "minecraft:copper_nautilus_armor": 300,
    # amethyst buds
    "minecraft:amethyst_cluster": 80, "minecraft:large_amethyst_bud": 40,
    "minecraft:medium_amethyst_bud": 30, "minecraft:small_amethyst_bud": 20,
    # armor-trim / smithing templates: treasure-only, duplication costs 7 diamonds
    # (~28k) so they sit well above that. The brutal ones (ancient city / trial
    # vault / bastion) cost the most.
    "minecraft:netherite_upgrade_smithing_template": 120000,  # bastion treasure
    "minecraft:silence_armor_trim_smithing_template": 150000,  # ancient city, 1% chest
    "minecraft:ward_armor_trim_smithing_template": 120000,     # ancient city
    "minecraft:flow_armor_trim_smithing_template": 100000,     # trial vault (ominous)
    "minecraft:bolt_armor_trim_smithing_template": 100000,     # trial vault (ominous)
    "minecraft:wayfinder_armor_trim_smithing_template": 80000, # trail ruins (rare)
    "minecraft:raiser_armor_trim_smithing_template": 80000,
    "minecraft:shaper_armor_trim_smithing_template": 80000,
    "minecraft:host_armor_trim_smithing_template": 80000,
}

# ---------------------------------------------------------------- pattern rules
# (compiled-regex on the path after the namespace, price). First match wins.
def _suf(*sfx):
    return re.compile("(" + "|".join(re.escape(s) for s in sfx) + ")$")

RULES = [
    (_suf("_log", "_stem", "_hyphae", "_wood"), 50),
    (re.compile(r"^stripped_.*(_log|_stem|_wood|_hyphae|_bamboo_block)$"), 50),
    (_suf("_leaves"), 2),
    (_suf("_sapling", "_propagule"), 30),
    (_suf("_pottery_sherd"), 2000),
    (re.compile(r"_armor_trim_smithing_template$"), 50000),  # treasure trims
    (_suf("_banner_pattern"), 500),
    (_suf("_horse_armor", "_nautilus_armor"), 500),
    (re.compile(r"^music_disc_"), 5000),
    (re.compile(r"(creeper|zombie|skeleton|piglin|player)_head$|skeleton_skull$"), 300),
    (re.compile(r"^dead_.*coral"), 10),
    (re.compile(r"coral(_block|_fan|_wall_fan)?$"), 30),
    (_suf("_concrete"), 10),
    (_suf("_concrete_powder"), 8),
    (re.compile(r"(tulip|orchid|daisy|allium|cornflower|poppy|dandelion|bluet|"
                r"lily_of_the_valley|wither_rose|torchflower|eyeblossom|lilac|peony|"
                r"rose_bush|sunflower|pink_petals|wildflowers|cactus_flower|"
                r"spore_blossom|chorus_flower|firefly_bush)$"), 10),
    (re.compile(r"(short_grass|tall_grass|fern|large_fern|dead_bush|^bush$|seagrass|"
                r"hanging_roots|^vine$|glow_lichen|sea_pickle|dry_grass|nether_sprouts|"
                r"_roots$|twisting_vines|weeping_vines|lily_pad|dripleaf|pale_hanging_moss|"
                r"_fungus$|sculk_vein)$"), 5),
    (re.compile(r"mushroom_block$|warped_wart_block$"), 15),
    (re.compile(r"_mushroom$|mushroom_stem$"), 8),
    (re.compile(r"shroomlight$"), 40),
    (re.compile(r"_petal_block$|_petals$"), 12),     # decorative petal blocks
    (re.compile(r"_sand$"), 5),                       # black/orange/white sand etc.
    (re.compile(r"rose_quartz"), 80),                # BOP rose-quartz gems/buds/clusters
    (re.compile(r"_bucket$"), 50),                   # modded fluid buckets
    (re.compile(r"_toolbox$"), 60),                  # create toolboxes
    (re.compile(r"_encased_shaft$"), 80),            # create encased shafts
    (re.compile(r"_valve_handle$"), 40),             # create valve handles
    (re.compile(r"(raw_|_ore$)"), 250),              # generic modded ore/raw fallback
    (re.compile(r"_ingot$|_gem$|_crystal$"), 250),
    (re.compile(r"potion$|tipped_arrow$"), 100),
]

# ---------------------------------------------------------------- category fallback
CATEGORY_DEFAULT = {
    "building_blocks": 20, "colored_blocks": 15, "natural_blocks": 12,
    "functional_blocks": 80, "redstone_blocks": 60, "tools_and_utilities": 300,
    "combat": 300, "food_and_drinks": 25, "ingredients": 40, "uncategorized": 30,
    "create.base": 120, "create.palettes": 20,
    "farmersdelight.farmersdelight": 30,
    "refurbished_furniture.creative_tab": 150, "biomesoplenty.main": 15,
}


def anchor_price(item_id, category):
    if item_id in EXACT:
        return EXACT[item_id]
    path = item_id.split(":", 1)[1] if ":" in item_id else item_id
    for rx, price in RULES:
        if rx.search(path):
            return price
    return CATEGORY_DEFAULT.get(category, 30)


# ---------------------------------------------------------------- farm-tier (sell)
# F0 rare/manual, F1 renewable-active, F2 AFK-automatable, F3 trivially-infinite
_F3 = re.compile(r"(cobblestone|^dirt$|gravel|^sand$|netherrack|rotten_flesh|"
                 r"^string$|cobbled_deepslate|^flint$|^stick$)")
_F2 = re.compile(r"(iron|gold|copper|redstone|coal|charcoal|bone|spider_eye|gunpowder|"
                 r"feather|leather|ink_sac|slime|honey|kelp|sugar_cane|bamboo|wheat|"
                 r"carrot|potato|beetroot|melon|pumpkin|_log$|_stem$|_wood$|_planks$|"
                 r"_plank$|quartz|andesite_alloy|zinc|brass|"
                 r"glowstone|blaze_rod|ender_pearl|nether_wart|sculk|cactus|seeds$|"
                 r"egg$|wool$|^beef$|^chicken$|^porkchop$|^mutton$|^cod$|^salmon$)")
_F0 = re.compile(r"(diamond|netherite|ancient_debris|nether_star|dragon|elytra|"
                 r"totem|heart_of_the_sea|echo_shard|trident|enchanted_golden_apple|"
                 r"heavy_core|wither_skeleton_skull|_pottery_sherd$|music_disc|"
                 r"beacon|conduit|amethyst|shulker_shell|nautilus_shell|"
                 r"smithing_template|armor_trim)")


def farm_tier(item_id):
    path = item_id.split(":", 1)[1] if ":" in item_id else item_id
    if _F0.search(path):
        return "F0"
    if _F3.search(path):
        return "F3"
    if _F2.search(path):
        return "F2"
    return "F1"


# Sell = a tiny per-tier FRACTION of buy. Because buy is arbitrage-free
# (crafted >= parts), proportional sell can't be gamed by crafting up, and the
# tiny F2/F3 fractions keep a maxed farm's sell income at/below the emerald farm.
# F0/F1 = % of (high) buy; F2 = small %; F3 = flat absolute (trivially-infinite
# bulk priced by hand, not as a fraction of buy).
SELL_FRACTION = {"F0": 0.50, "F1": 0.25, "F2": 0.01}
SELL_F3_FLAT = 1.0
SELL_FLOOR = 0.01

# Currency-equivalent items: sold at face value (their emerald content), not by
# farm-tier %, so bundled money behaves like money.
CURRENCY_FACE = {"minecraft:emerald_block": 9}


def root_sell(item_id, buy):
    """Sell value for a ROOT item (no construction recipe) from its farm tier.
    Crafted items don't use this — their sell is the sum of ingredient sells
    (computed in the deriver), which keeps selling arbitrage-free."""
    if item_id in CURRENCY_FACE:
        return CURRENCY_FACE[item_id]
    if not buy or buy <= 0:
        return SELL_FLOOR
    tier = farm_tier(item_id)
    s = SELL_F3_FLAT if tier == "F3" else buy * SELL_FRACTION[tier]
    return s
