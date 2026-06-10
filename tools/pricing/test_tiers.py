"""unittest suite for tiers.py pricing logic. Run from repo root:
    python tools/pricing/test_tiers.py -v
"""
import os, sys, unittest
sys.path.insert(0, os.path.dirname(__file__))
import tiers as T


class TestLadders(unittest.TestCase):
    def test_effort_factors(self):
        self.assertEqual(T.EFFORT, {1: 1, 2: 3, 3: 8, 4: 20, 5: 40})

    def test_rarity_factors(self):
        self.assertEqual(T.RARITY, {1: 1, 2: 2, 3: 5, 4: 12, 5: 40})

    def test_formula_price(self):
        # raw_gem base 5 × effort4(20) × rarity5(40) = 4000
        self.assertEqual(T.formula_price(("raw_gem", 4, 5)), 4000)
        # raw_stone base 5 × effort1(1) × rarity1(1) = 5
        self.assertEqual(T.formula_price(("raw_stone", 1, 1)), 5)


class TestClassify(unittest.TestCase):
    def test_cell_override_wins(self):
        self.assertEqual(T.classify("minecraft:diamond", "ingredients"), ("raw_gem", 4, 5))

    def test_regex_rule(self):
        # any *_ore falls to the raw_metal ore rule
        band, e, r = T.classify("minecraft:iron_ore", "natural_blocks")
        self.assertEqual(band, "raw_metal")

    def test_category_default(self):
        # an unknown id in food_and_drinks uses that category's default cell
        cell = T.classify("minecraft:made_up_food", "food_and_drinks")
        self.assertEqual(cell, T.CATEGORY_DEFAULT["food_and_drinks"])

    def test_unknown_category_falls_to_default_band(self):
        cell = T.classify("minecraft:mystery", "no_such_category")
        self.assertEqual(cell[0], "default")


class TestRootBuy(unittest.TestCase):
    def test_reference_prices(self):
        cases = {
            "minecraft:cobblestone": 5,
            "minecraft:copper_ingot": 200,
            "minecraft:diamond": 4000,
            "minecraft:ancient_debris": 4000,
        }
        for item, expected in cases.items():
            self.assertEqual(T.root_buy(item, "ingredients"), expected, item)

    def test_exact_overrides_formula(self):
        self.assertEqual(T.root_buy("minecraft:nether_star", "functional_blocks"), 700000)
        self.assertEqual(T.root_buy("minecraft:emerald", "ingredients"), 1)


class TestExploitGuards(unittest.TestCase):
    """Pins added for known conversion exploits (see arbitrage.py): each buy
    price must clear the expected sell value of what the item converts into.
    EVs were measured from the Create recipe data in work/conversions.json."""

    def assertCovers(self, evs):
        for item, ev in evs.items():
            self.assertGreaterEqual(T.formula_price(T.CELL[item]), ev, item)

    def test_create_ore_stones_cover_crush_yield(self):
        self.assertCovers({"create:crimsite": 118, "create:veridium": 72,
                           "create:ochrum": 59, "create:asurine": 27})

    def test_ores_cover_create_crush_yield(self):
        self.assertCovers({
            "minecraft:deepslate_iron_ore": 601,
            "minecraft:deepslate_gold_ore": 608,
            "minecraft:deepslate_copper_ore": 587,
            "minecraft:diamond_ore": 4907,
            "minecraft:deepslate_diamond_ore": 6307,
            "minecraft:amethyst_cluster": 225,
        })

    def test_horse_armors_cover_recycle_crush_yield(self):
        self.assertCovers({
            "minecraft:diamond_horse_armor": 3678,
            "minecraft:golden_horse_armor": 890,
            "minecraft:iron_horse_armor": 710,
            "minecraft:copper_horse_armor": 253,
        })

    def test_crushed_raw_metals_carry_ingot_value(self):
        pairs = [("create:crushed_raw_iron", "minecraft:iron_ingot"),
                 ("create:crushed_raw_gold", "minecraft:gold_ingot"),
                 ("create:crushed_raw_copper", "minecraft:copper_ingot"),
                 ("create:crushed_raw_zinc", "create:zinc_ingot")]
        for crushed, ingot in pairs:
            self.assertEqual(T.formula_price(T.CELL[crushed]),
                             T.formula_price(T.CELL[ingot]), crushed)

    def test_template_sells_below_duplication_cost(self):
        # one template + 7 diamonds + a base block duplicates into two (~28005
        # marginal cost per copy); selling above that is a money printer
        for k, v in T.EXACT_SELL.items():
            if k.endswith("smithing_template"):
                self.assertLess(v, 28000, k)


class TestRootSell(unittest.TestCase):
    def test_rarity_scaled_fraction(self):
        # rarity 5 -> 70% of buy
        self.assertAlmostEqual(T.root_sell("minecraft:diamond", 4000, "ingredients"), 4000 * 0.70)

    def test_currency_face(self):
        self.assertEqual(T.root_sell("minecraft:emerald_block", 9, "building_blocks"), 9)

    def test_floor(self):
        self.assertEqual(T.root_sell("minecraft:anything", 0, "ingredients"), T.SELL_FLOOR)

    def test_exact_only_item_sell_uses_category_default_rarity(self):
        # dragon_egg has an EXACT buy but no CELL entry; its sell rarity comes from the
        # category default. functional_blocks default is (default,3,3) -> rarity 3 -> 40%.
        self.assertAlmostEqual(
            T.root_sell("minecraft:dragon_egg", 1000000, "functional_blocks"),
            1000000 * T.SELL_FRACTION[3],
        )


if __name__ == "__main__":
    unittest.main()
