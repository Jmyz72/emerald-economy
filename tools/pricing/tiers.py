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

Note: layer 1 (EXACT) is applied in root_buy(); classify() handles only layers 2-4
(it returns a (band, effort, rarity) cell, and EXACT items have no cell — they bypass
the formula entirely).

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

# Per-item SELL overrides (buy still comes from EXACT/recipe). Needed where the
# rarity-fraction sell would exceed a cheap in-game reproduction cost. Smithing
# templates duplicate for 7 diamonds + a base block (~28k): selling copies at
# 55% of their 100k-150k EXACT buy would be a ~50k-per-copy money printer, so
# they sell below the duplication cost instead.
EXACT_SELL = {
    # quartz is renewable at ~10 from BULK SAND via Create: haunt sand (5) ->
    # soul_sand, wash soul_sand -> 0.5 quartz EV. Selling at the rarity fraction
    # (80) would be a fully automatable money printer; the buy price stays at the
    # mining-effort 200, only the payout is capped below the chain cost.
    "minecraft:quartz": 8,
    # andesite_alloy mixes 2-from-(andesite + cheapest nugget) for ~14 each; the
    # 75 CELL buy is a deliberate tech-tree gate, but the payout must sit under
    # the mixing cost.
    "create:andesite_alloy": 10,
    "minecraft:netherite_upgrade_smithing_template": 20000,
    "minecraft:silence_armor_trim_smithing_template": 20000,
    "minecraft:ward_armor_trim_smithing_template": 20000,
    "minecraft:flow_armor_trim_smithing_template": 20000,
    "minecraft:bolt_armor_trim_smithing_template": 20000,
}

# ---------------------------------------------------------------- EXACT (final price)
# Wins over both the formula and any recipe. Currency + genuine one-offs that sit above
# the ~8000 formula ceiling or carry special meaning. Keep this SHORT.
EXACT = {
    "minecraft:emerald": 1,
    "minecraft:emerald_block": 9,
    "minecraft:nether_star": 700000,
    # 3 skulls + 4 soul sand summon a Wither that drops a nether star (sell
    # 490000); at the 240 mob-head rule price that was a ~660x money printer.
    # 3 x 175000 = 525000 keeps the summon above the star's payout.
    "minecraft:wither_skeleton_skull": 175000,
    # damaged anvils work exactly like fresh ones (16300); the 200 functional-tab
    # default sold a near-perfect substitute for 1.2% of the price.
    "minecraft:chipped_anvil": 10800,
    "minecraft:damaged_anvil": 5400,
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
    "minecraft:red_sand": ("raw_stone", 2, 1),   # Create washing: 0.375 gold_nugget EV ~11
    "minecraft:gravel": ("raw_stone", 1, 1),
    "minecraft:netherrack": ("raw_stone", 1, 1),
    "minecraft:stone": ("raw_stone", 1, 2),
    "minecraft:deepslate": ("raw_stone", 1, 2),
    "minecraft:cobbled_deepslate": ("raw_stone", 1, 1),
    # clay_ball's only recipe is the clay-block decompose, which prune_decompose
    # drops; pin it as the dug raw material so clay/terracotta stay cheap.
    "minecraft:clay_ball": ("raw_stone", 1, 1),
    # Create haunting/washing converts these from trivial blocks, so their value
    # tracks the source block, not their natural rarity. (soul_sand -> quartz
    # washing is closed on the quartz SELL side via EXACT_SELL: the whole chain
    # starts from 5-emerald sand, so no soul_sand buy price can close it.)
    "minecraft:soul_sand": ("raw_stone", 1, 1),    # haunted from sand
    "minecraft:soul_soil": ("raw_stone", 1, 2),    # washed from dirt
    # ice is silk-touch-harvested ice, not 9 compacted snow blocks (its only
    # construction recipe, which priced it at 198); packed ice is pinned to the same
    # value because Create washing converts ice -> packed_ice 1:1.
    "minecraft:ice": ("natural_block", 1, 2),
    "minecraft:packed_ice": ("natural_block", 1, 2),
    "minecraft:obsidian": ("raw_stone", 3, 3),
    # raw metals / ores. The ore, raw form, and ingot of a metal must share a value:
    # they convert 1:1 by mining/smelting, so any gap is a buy-low/smelt/sell-high
    # arbitrage. The *_ingot entries also MUST be here (not left to the recipe graph):
    # the "9 ingots from 1 block" decompose recipe otherwise turns the storage block
    # into a spurious root and collapses the ingot to ~block/9. (copper's ore is left
    # to the generic _ore$ rule since copper raw/ingot are already 200 = that rule.)
    "minecraft:coal": ("raw_metal", 2, 2),
    "minecraft:charcoal": ("raw_metal", 2, 2),
    "minecraft:raw_copper": ("raw_metal", 3, 3),
    "minecraft:copper_ingot": ("raw_metal", 3, 3),
    "minecraft:raw_iron": ("raw_metal", 3, 4),
    "minecraft:iron_ingot": ("raw_metal", 3, 4),
    "minecraft:iron_ore": ("raw_metal", 3, 4),
    "minecraft:raw_gold": ("raw_metal", 3, 4),
    "minecraft:gold_ingot": ("raw_metal", 3, 4),
    "minecraft:gold_ore": ("raw_metal", 3, 4),
    # Deepslate metal ores: Create crushing yields 2.25x crushed iron/gold (sell 264
    # each) or 7.25x crushed copper (sell 80 each) - EV ~590-610, so pricing them at
    # the regular-ore 480 is a buy/crush/smelt/sell exploit. One tier up covers it.
    # (Regular iron/gold ore crush EV is ~469/~476, just under their 480.)
    "minecraft:deepslate_iron_ore": ("raw_metal", 4, 4),
    "minecraft:deepslate_gold_ore": ("raw_metal", 4, 4),
    # gold_nugget pinned (= ~ingot/9, slightly above so crafting 9->ingot is no
    # arbitrage) because Create crushing of gilded_blackstone / nether_gold_ore yields
    # 18 nuggets and would otherwise set gold_nugget at ~block/18.
    "minecraft:gold_nugget": ("raw_metal", 2, 3),
    # iron/copper nuggets pinned the same way: with the reciprocal ingot<->nugget
    # recipes pruned from the pricing graph (see prune_decompose in derive.py), their
    # cheapest remaining recipe is smelting gear (~210), well above ingot/9.
    "minecraft:iron_nugget": ("raw_metal", 2, 3),    # ~ iron_ingot/9 (53), pinned above
    "minecraft:copper_nugget": ("raw_metal", 1, 3),  # ~ copper_ingot/9 (22), pinned above
    # Create crushing turns 1 of these into 18 gold_nuggets (= 2 ingots), so they must
    # be priced at their crushed yield, else buy-block/crush/sell-metal is profitable.
    "minecraft:gilded_blackstone": ("raw_metal", 4, 4),
    "minecraft:nether_gold_ore": ("raw_metal", 4, 4),
    "minecraft:redstone": ("raw_metal", 2, 3),
    "minecraft:lapis_lazuli": ("raw_metal", 2, 3),
    "minecraft:quartz": ("raw_metal", 3, 3),
    # Gem / multi-drop ores: the generic _ore$ rule's flat 200 is far below what these
    # yield when mined (diamond_ore drops a 4000 diamond; lapis/redstone/copper drop
    # 2-9 units), so without these they're a buy-ore/mine/sell-drop arbitrage. Priced at
    # ~mining yield. (coal/emerald/quartz ores yield <=200 so the flat rule is safe there.)
    # For diamond the binding yield is Create crushing: 1.75 / 2.25 diamonds expected
    # (sell 2800 each) = EV ~4907 / ~6307, so both diamond ores sit one tier above
    # the single-drop 4000.
    "minecraft:diamond_ore": ("raw_gem", 5, 5),
    "minecraft:deepslate_diamond_ore": ("raw_gem", 5, 5),
    "minecraft:lapis_ore": ("raw_metal", 3, 4),
    "minecraft:deepslate_lapis_ore": ("raw_metal", 3, 4),
    "minecraft:redstone_ore": ("raw_metal", 3, 4),
    "minecraft:deepslate_redstone_ore": ("raw_metal", 3, 4),
    "minecraft:copper_ore": ("raw_metal", 3, 4),
    "minecraft:deepslate_copper_ore": ("raw_metal", 4, 4),  # crush EV ~587 (7.25x80)
    # Create alloys: ingot/raw forms pinned (same block-decompose collapse as vanilla).
    # brass ~= copper+zinc per unit (mixing makes 2 brass from 1 copper + 1 zinc).
    "create:zinc_ingot": ("raw_metal", 3, 3),
    "create:raw_zinc": ("raw_metal", 3, 3),
    "create:brass_ingot": ("raw_metal", 3, 3),
    # Create ore-bearing stones: each crushes into crushed_raw metal + a nugget at
    # 20-80% chance. Left to the palette default (30) they're a buy-stone/crush/
    # smelt/sell-ingot money printer - same trap as gilded_blackstone. Priced above
    # the expected crush yield noted per stone.
    "create:crimsite": ("raw_metal", 3, 3),    # EV ~118 (0.4 cr.iron + 0.4 nugget)
    "create:veridium": ("raw_metal", 3, 3),    # EV ~71  (0.8 cr.copper + 0.8 nugget)
    "create:ochrum": ("raw_metal", 2, 3),      # EV ~59  (0.2 cr.gold + 0.2 nugget)
    "create:asurine": ("raw_metal", 2, 3),     # EV ~27  (0.3 cr.zinc + 0.3 nugget)
    # Crushed raw metals smelt 1:1 into the anchored ingots, so they must carry the
    # ingot's value. Left to the recipe graph they price off their cheapest producer
    # (crushed_raw_iron was 31.5 via crimsite!) while the ingot sells at 264.
    "create:crushed_raw_iron": ("raw_metal", 3, 4),
    "create:crushed_raw_gold": ("raw_metal", 3, 4),
    "create:crushed_raw_copper": ("raw_metal", 3, 3),
    "create:crushed_raw_zinc": ("raw_metal", 3, 3),
    # Units that have a "9 units from 1 _block" decompose recipe but no CELL of their own
    # collapse (the block becomes a spurious root). Pin the unit; the block then prices up
    # from it. andesite_alloy is Create's foundational material (gates the whole tech tree).
    "create:andesite_alloy": ("raw_metal", 2, 3),     # ~= andesite + nugget mixing cost
    "create:experience_nugget": ("raw_metal", 2, 2),
    "minecraft:bone_meal": ("mob_drop", 1, 1),        # ~= bone / 3
    "minecraft:resin_clump": ("mob_drop", 2, 2),      # pale-oak/creaking drop (estimate)
    "minecraft:glowstone_dust": ("raw_metal", 3, 3),
    # raw gems
    "minecraft:diamond": ("raw_gem", 4, 5),
    "minecraft:amethyst_shard": ("raw_gem", 2, 3),
    # the full cluster crushes into 7.5 shards EV (sell 30 each = 225), so it
    # can't share the 75 cluster/bud rule price
    "minecraft:amethyst_cluster": ("raw_gem", 3, 4),
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

    # --- additional mob drops / simple ingredients ---------------------------
    "minecraft:feather": ("mob_drop", 1, 1),          # chicken drop, trivial
    "minecraft:ink_sac": ("mob_drop", 2, 2),          # squid
    # glow_ink at (3,3)=240 was an arb: Create haunting converts ink_sac (36) into
    # glow_ink_sac, so its sell must stay at/below the ink_sac buy.
    "minecraft:glow_ink_sac": ("mob_drop", 2, 3),     # haunted from ink_sac
    "minecraft:honeycomb": ("plant_produce", 2, 2),   # bee farm
    "minecraft:rabbit_foot": ("mob_drop", 3, 4),      # rare rabbit drop
    "minecraft:rabbit_hide": ("mob_drop", 2, 2),
    "minecraft:phantom_membrane": ("mob_drop", 3, 3), # phantom (night/insomnia)
    "minecraft:nautilus_shell": ("treasure", 3, 4),   # fishing/drowned, gates conduit
    # shard at (3,3)=240 was an arb: Create haunting turns 1 lapis (75) into 0.75
    # shard + 0.125 crystals. crystals must STAY >= ~176 though: crushing them
    # yields 2x quartz (sell 80 each).
    "minecraft:prismarine_shard": ("mob_drop", 2, 3), # haunted from lapis
    "minecraft:prismarine_crystals": ("mob_drop", 3, 3),
    "minecraft:turtle_scute": ("mob_drop", 3, 3),     # baby turtle growth
    "minecraft:armadillo_scute": ("mob_drop", 2, 2),  # brushing armadillo
    "minecraft:dragon_breath": ("mob_drop", 4, 4),    # bottle ender dragon breath
    "minecraft:echo_shard": ("treasure", 4, 4),       # ancient city loot
    "minecraft:breeze_rod": ("mob_drop", 4, 4),       # trial-chamber breeze drop
    "minecraft:experience_bottle": ("treasure", 3, 3),
    "minecraft:enchanted_book": ("treasure", 3, 3),

    # --- trial-chamber keys (vault gating) -----------------------------------
    "minecraft:trial_key": ("treasure", 4, 4),
    "minecraft:ominous_trial_key": ("treasure", 5, 4),

    # --- combat / utility one-offs -------------------------------------------
    "minecraft:trident": ("mob_drop", 4, 4),          # drowned drop
    # Horse armors: Create crushing recycles them into their metals (diamond
    # horse armor -> 1.3 diamonds EV!), so the generic 200 rule price is a
    # money printer. Pinned above recycle yield (EV sell noted).
    "minecraft:diamond_horse_armor": ("treasure", 5, 4),   # EV ~3678
    "minecraft:golden_horse_armor": ("treasure", 3, 4),    # EV ~890
    "minecraft:iron_horse_armor": ("treasure", 3, 4),      # EV ~710
    "minecraft:copper_horse_armor": ("treasure", 3, 3),    # EV ~253
    "minecraft:goat_horn": ("treasure", 3, 4),        # goat ram / loot
    "minecraft:snowball": ("raw_stone", 1, 1),        # snow, trivial
    "minecraft:firework_star": ("default", 2, 2),

    # --- archaeology / suspicious blocks -------------------------------------
    "minecraft:suspicious_gravel": ("treasure", 3, 3),
    "minecraft:suspicious_sand": ("treasure", 3, 3),   # was falling to _sand$ rule (5)
    # ominous_bottle gates ominous trials/vault loot; the food-tab default (24)
    # wildly undersold it. Raid-captain renewable, so treasure mid-tier.
    "minecraft:ominous_bottle": ("treasure", 3, 3),

    # --- written/filled items: cheap player-made curios ----------------------
    "minecraft:filled_map": ("default", 2, 2),
    "minecraft:written_book": ("default", 2, 2),

    # --- BoP ore-chunk gems (rose quartz raw chunk) --------------------------
    "biomesoplenty:rose_quartz_chunk": ("raw_gem", 2, 3),

    # --- natural-block one-offs that the natural_block default mis-tiers ------
    "minecraft:wet_sponge": ("natural_block", 4, 4),   # ocean-monument gated
    "minecraft:bee_nest": ("natural_block", 3, 3),     # find a bee tree
    "minecraft:slime_block": ("natural_block", 2, 3),  # swamp/slime-chunk slimeballs
    # slime_ball's only recipe is the block decompose, which prune_decompose drops;
    # pin it at ~block/9 so it doesn't fall to the 90-emerald ingredients default.
    "minecraft:slime_ball": ("mob_drop", 1, 2),
    "minecraft:nether_wart": ("plant_produce", 3, 3),  # nether fortress, brewing gate
    "minecraft:cocoa_beans": ("plant_produce", 2, 2),
    "minecraft:carved_pumpkin": ("plant_produce", 2, 2),  # sheared pumpkin, not a 12 natural
    "minecraft:chorus_flower": ("natural_block", 3, 3),   # End-gated growth block
    "minecraft:turtle_egg": ("natural_block", 3, 3),      # silk-touch beach raid
    # sniffer-dug ancient seeds: the 12-emerald natural default ignored that the
    # sniffer line is gated behind an 8000 egg
    "minecraft:pitcher_pod": ("treasure", 3, 3),
    "minecraft:torchflower_seeds": ("treasure", 3, 3),
    "minecraft:glow_berries": ("plant_produce", 2, 2),
    "minecraft:sweet_berries": ("plant_produce", 2, 2),
    "minecraft:cobweb": ("mob_drop", 3, 3),            # shears + mineshaft/spawner
}

# ---------------------------------------------------------------- regex rules (ordered)
# Compiled against the path after the namespace. First match wins. Each maps a family of
# ids to a (band, effort, rarity) cell.
def _suf(*sfx):
    return re.compile("(" + "|".join(re.escape(s) for s in sfx) + ")$")

RULES = [
    # --- wood / foliage -------------------------------------------------------
    (_suf("_log", "_stem", "_hyphae", "_wood", "bamboo_block"), ("plant_produce", 2, 3)),
    (re.compile(r"^stripped_.*(_log|_stem|_wood|_hyphae|_bamboo_block)$"), ("plant_produce", 2, 3)),
    (_suf("_leaves"), ("plant_produce", 1, 1)),
    (_suf("_sapling", "_propagule"), ("plant_produce", 1, 3)),

    # --- pottery sherds: archaeology-only treasure (brush suspicious blocks) ---
    (re.compile(r"_pottery_sherd$"), ("treasure", 4, 4)),
    # --- armor-trim smithing templates: structure-loot treasure (rest in EXACT) ---
    (re.compile(r"_armor_trim_smithing_template$"), ("treasure", 4, 4)),
    # --- banner patterns: loot/trade curios ----------------------------------
    (re.compile(r"_banner_pattern$"), ("treasure", 3, 3)),
    # --- music discs: loot/creeper-drop collectibles -------------------------
    (re.compile(r"^music_disc_|^disc_fragment"), ("treasure", 3, 4)),

    # --- ores / raw metals / ingots ------------------------------------------
    (re.compile(r"_ore$"), ("raw_metal", 3, 3)),
    (re.compile(r"^raw_"), ("raw_metal", 3, 3)),
    (re.compile(r"^crushed_raw_"), ("raw_metal", 3, 3)),  # Create crushed raw ores
    (re.compile(r"_ingot$|_gem$|_crystal$"), ("raw_metal", 3, 3)),
    (re.compile(r"_nugget$"), ("raw_metal", 2, 2)),  # 1/9 of an ingot — keep cheap

    # --- copper oxidation states (exposed/weathered/oxidized copper family) ---
    # All trace back to copper; price at the copper-ingot cell so variants stay flat.
    (re.compile(r"^(exposed|weathered|oxidized)_copper"), ("raw_metal", 3, 3)),

    # --- stone-ish blocks -----------------------------------------------------
    # concrete: washing powder -> concrete is free (vanilla water / Create), so a
    # root-priced concrete must sell below the ~5.4 crafted powder, not at 30.
    (re.compile(r"_concrete$|_concrete_powder$"), ("raw_stone", 1, 2)),
    (re.compile(r"_sand$"), ("raw_stone", 1, 1)),
    # basalt/blackstone are renewable from trivial inputs (basalt generators;
    # Create haunting cobblestone -> blackstone), so they price near cobble.
    (re.compile(r"^(basalt|blackstone)$"), ("raw_stone", 1, 2)),
    (re.compile(r"^(end_stone|brimstone)$"), ("raw_stone", 2, 2)),

    # --- mob heads / skulls ---------------------------------------------------
    (re.compile(r"(creeper|zombie|skeleton|piglin|player)_head$|skeleton_skull$"),
        ("mob_drop", 3, 3)),

    # --- coral ----------------------------------------------------------------
    (re.compile(r"coral(_block|_fan|_wall_fan)?$"), ("natural_block", 2, 2)),

    # --- wool / carpet: sheared sheep or string-crafted, cheap ---------------
    (re.compile(r"_wool$|^wool$|_carpet$"), ("plant_produce", 2, 2)),

    # --- flowers / small plants (vanilla + BoP petal-blocks & wildflowers) ----
    (re.compile(r"(tulip|orchid|allium|cornflower|poppy|dandelion|daisy|"
                r"lily_of_the_valley|rose_bush|sunflower|pink_petals|wildflowers?|"
                r"_flower_petal_block|hibiscus|hydrangea|lavender|cosmos|marigold|"
                r"daffodil|violet|clover|goldenrod|lilac|peony|azure_bluet|"
                r"eyeblossom|wither_rose|torchflower|spore_blossom|"
                r"wilted_lily|burning_blossom|origin_rose|icy_iris|glowflower)$"),
        ("plant_produce", 1, 1)),
    (re.compile(r"(short_grass|tall_grass|_grass|fern|dead_bush|seagrass|vine|kelp|"
                r"_roots$|sprouts?$|barley|reed|cattail|bramble|bush|shrub|"
                r"sea_oats|spanish_moss|hanging_moss|hanging_roots|webbing|"
                r"glow_lichen|nether_sprouts|twisting_vines|weeping_vines|"
                r"dead_branch|dead_grass|tundra_shrub)$"),
        ("plant_produce", 1, 1)),
    (re.compile(r"_mushroom$|mushroom_block$|mushroom_stem$|fungus$|toadstool|"
                r"glowshroom"), ("plant_produce", 2, 2)),

    # --- amethyst / rose-quartz / dripstone clusters & buds (mining w/ care) --
    (re.compile(r"(amethyst|rose_quartz).*(cluster|bud|chunk)$|^pointed_dripstone$"),
        ("raw_gem", 2, 3)),

    # --- froglights: nether (magma cube + frog) gated decorative blocks -------
    (re.compile(r"_froglight$"), ("natural_block", 3, 3)),
    # --- sculk family: deep-dark gated --------------------------------------
    (re.compile(r"^sculk(_catalyst|_sensor|_shrieker|_vein)?$"), ("natural_block", 4, 4)),
    # --- nether decorative growth / nylium / wart blocks ----------------------
    (re.compile(r"^(crimson|warped)_nylium$|_wart_block$|^shroomlight$|^soul_soil$"),
        ("natural_block", 3, 2)),
    (re.compile(r"^crying_obsidian$"), ("raw_stone", 3, 3)),

    # --- vanilla equipment that slipped through recipe pricing ---------------
    # Material-tiered armor/tools/weapons. Chainmail has no craft recipe (loot/trade),
    # so it gets a standalone cell; iron/gold equipment is mid-tier gear.
    (re.compile(r"^chainmail_"), ("default", 4, 4)),
    (re.compile(r"^iron_(sword|spear|pickaxe|axe|shovel|hoe|helmet|chestplate|"
                r"leggings|boots|horse_armor)$"), ("raw_metal", 3, 3)),
    (re.compile(r"^golden_(sword|spear|pickaxe|axe|shovel|hoe|helmet|chestplate|"
                r"leggings|boots)$|^gold(en)?_horse_armor$"), ("raw_metal", 3, 3)),
    (re.compile(r"^(copper|diamond|iron|golden)_(horse|nautilus)_armor$"),
        ("default", 3, 3)),

    # --- buckets of mobs/fluids: travel + capture, single-use novelty --------
    (re.compile(r"_bucket$"), ("default", 3, 3)),

    # --- eggs (chicken/blue/brown): trivial passive-mob drop -----------------
    (re.compile(r"^(blue_|brown_)?egg$"), ("mob_drop", 2, 2)),

    # --- prepared-food slices/portions across content mods -------------------
    # Anything that reads as cooked/prepared food belongs in the food band, not
    # the generic "default" tab fallback (which over-prices furniture-mod food).
    (re.compile(r"_slice$|_cuts$|_chops$|^bacon$|^ham$|_pie$|_pie_slice$|"
                r"^toast$|_sandwich$|_pizza_slice$|_flour$|^minced_beef$|"
                r"^roast_chicken$|^honey_glazed_ham$|_cheesecake_slice$"),
        ("plant_produce", 2, 2)),

    # --- Create decorative palette blocks (pillars, alloy/metal blocks) ------
    (re.compile(r"_pillar$|^(andesite_alloy|brass|zinc)_block$|^bound_cardboard_block$"),
        ("raw_stone", 2, 2)),
    # --- Create toolboxes (16 dye variants, identical utility) ---------------
    (re.compile(r"_toolbox$"), ("default", 2, 3)),
    # --- Create encased shafts / machine casings -----------------------------
    (re.compile(r"_encased_shaft$|_casing$"), ("default", 2, 3)),
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
    if effort not in EFFORT or rarity not in RARITY:
        raise ValueError(f"bad cell {cell!r}: effort must be 1-5 and rarity 1-5")
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
