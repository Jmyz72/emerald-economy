# Price Generator Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the hand-guessed `anchors.py` root-pricing layer with a principled `effort × rarity × band_base` formula, so root prices reflect how hard/rare an item is to obtain instead of arbitrary numbers.

**Architecture:** A new `tiers.py` module owns the effort/rarity ladders, per-band base prices, rarity-scaled sell fractions, and a layered classifier (`EXACT` price overrides → per-item cell → regex rules → per-category default) that maps any root item to a `(band, effort, rarity)` cell. `derive.py`'s root-pricing path is rewired to call `tiers` instead of `anchors`; the recipe-graph derivation for crafted items is untouched. `audit.py` becomes the calibration/worklist QA surface. `anchors.py` is deleted.

**Tech Stack:** Standalone Python 3 (3.14 present), no third-party deps. Tests use the built-in `unittest` module (pytest is not installed). All commands run from the repo root `E:\Projects\Minecraft\Emerald-Economy-1.5.1`.

**Reference design:** `docs/superpowers/specs/2026-06-10-price-generator-redesign-design.md`

---

## File Structure

- `tools/pricing/extract.py` — **unchanged.** Recipes + tags from jars → `work/recipes.json`, `work/tags.json`.
- `tools/pricing/tiers.py` — **NEW.** Ladders, band bases, sell fractions, classifier, and the public functions `classify(id, category)`, `formula_price(cell)`, `root_buy(id, category)`, `root_sell(id, buy, category)`. Replaces `anchors.py`.
- `tools/pricing/derive.py` — **MODIFIED.** Root buy/sell path calls `tiers`; recipe recursion, EXACT-wins, `base_copper`, `round_buy`, `--write` unchanged.
- `tools/pricing/audit.py` — **MODIFIED.** Reports each root's resolved `(band, effort, rarity)` + price source, flags category-default-classified roots as the tuning worklist.
- `tools/pricing/test_tiers.py` — **NEW.** `unittest` tests for the pure pricing logic.
- `tools/pricing/anchors.py` — **DELETED** in the final task once nothing imports it.

---

## Task 1: Create `tiers.py` with ladders, bands, and the classifier

**Files:**
- Create: `tools/pricing/tiers.py`
- Test: `tools/pricing/test_tiers.py`

- [ ] **Step 1: Write the failing test**

Create `tools/pricing/test_tiers.py`:

```python
"""unittest suite for tiers.py pricing logic. Run from repo root:
    python tools/pricing/test_tiers.py -v
"""
import os, sys, unittest
sys.path.insert(0, os.path.join(os.path.dirname(__file__)))
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


class TestRootSell(unittest.TestCase):
    def test_rarity_scaled_fraction(self):
        # rarity 5 -> 70% of buy
        self.assertAlmostEqual(T.root_sell("minecraft:diamond", 4000, "ingredients"), 4000 * 0.70)

    def test_currency_face(self):
        self.assertEqual(T.root_sell("minecraft:emerald_block", 9, "building_blocks"), 9)

    def test_floor(self):
        self.assertEqual(T.root_sell("minecraft:anything", 0, "ingredients"), T.SELL_FLOOR)


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python tools/pricing/test_tiers.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'tiers'`

- [ ] **Step 3: Write `tiers.py`**

Create `tools/pricing/tiers.py`:

```python
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `python tools/pricing/test_tiers.py -v`
Expected: PASS — all tests OK. (`copper_ingot` → CELL `(raw_metal,3,3)` → `5×8×5=200`; `diamond` → `5×20×40=4000`; `nether_star` → EXACT 700000.)

- [ ] **Step 5: Commit**

```bash
git add tools/pricing/tiers.py tools/pricing/test_tiers.py
git commit -m "feat(pricing): add tiers.py effort×rarity root pricer with tests"
```

---

## Task 2: Rewire `derive.py` to use `tiers` instead of `anchors`

**Files:**
- Modify: `tools/pricing/derive.py` (import line, `Deriver.price` root fallback, `Deriver.sell`)

The deriver currently calls `anchors` in four places: `A.EXACT` (anchors-win check in `price`), `A.anchor_price` (root buy fallback in `price`), `A.CURRENCY_FACE` + `A.root_sell` + `A.SELL_FLOOR` (in `sell`). We point all of these at `tiers`. The recipe recursion, cycle detection, `base_copper`, `round_buy`, diagnostics, and `--write` stay exactly as they are.

**Critical:** explicitly-classified roots (`EXACT` *and* `CELL`) must **win over recipes**, exactly as the old `EXACT` table did. Without this, a decompose recipe like `diamond_block → 9 diamond` (kept as `crafting_shapeless`) turns `diamond_block` into a spurious root and prices `diamond` at ~3.5 instead of 4000. The old code protected raw materials by listing them in `EXACT`; here that protected set is `EXACT ∪ CELL`. So `price()` checks `CELL` membership up front (using the formula price) and returns before it ever tries recipes. `RULES`/category-default items do *not* get this treatment — they have no decompose loops (no recipe produces an ore/log/flower), so they reach the formula naturally as true roots.

- [ ] **Step 1: Change the import**

In `tools/pricing/derive.py`, replace:

```python
import anchors as A
```

with:

```python
import tiers as T
```

- [ ] **Step 2: Update the EXACT-wins check and root fallback in `price()`**

In `Deriver.price`, replace this block:

```python
        # anchors win, unconditionally
        if item in A.EXACT:
            self.memo[item] = A.EXACT[item]
            self.via[item] = "anchor"
            return self.memo[item]
```

with (note the added `CELL` check — explicitly-classified roots win over recipes, preventing decompose-loop undervaluation):

```python
        # EXACT overrides win, unconditionally (currency + true one-offs)
        if item in T.EXACT:
            self.memo[item] = float(T.EXACT[item])
            self.via[item] = "anchor"
            return self.memo[item]
        # explicitly-classified roots (raw mats) win over recipes, so decompose
        # recipes (e.g. block -> 9 ingots) can't undervalue them via a spurious root
        if item in T.CELL:
            self.memo[item] = float(T.formula_price(T.CELL[item]))
            self.via[item] = "anchor"
            return self.memo[item]
```

Then, further down in the same method, replace the no-recipe root fallback:

```python
        else:
            # no recipe resolved -> treat as root
            self.memo[item] = float(A.anchor_price(item, cat))
            self.via[item] = "anchor"
            self.best_inputs[item] = None
```

with:

```python
        else:
            # no recipe resolved -> treat as root, price by effort×rarity formula
            self.memo[item] = T.root_buy(item, cat)
            self.via[item] = "anchor"
            self.best_inputs[item] = None
```

- [ ] **Step 3: Update `sell()` to use `tiers` (and pass the category)**

In `Deriver.sell`, replace the whole method body's anchor references. The current method:

```python
    def sell(self, item):
        """Crafted item sell = sum of ingredient sells (arbitrage-free); root
        sell = farm-tier value. Capped just under buy."""
        if item in self.sell_memo:
            return self.sell_memo[item]
        if item in A.CURRENCY_FACE:
            self.sell_memo[item] = A.CURRENCY_FACE[item]
            return self.sell_memo[item]
        buy = self.price(item)
        inputs = self.best_inputs.get(item)
        if not inputs or item in self.sell_stack:   # root or cycle
            s = A.root_sell(item, buy)
        else:
            self.sell_stack.add(item)
            s = 0.0
            for who, n in inputs:
                s += self.sell(who) * n
            self.sell_stack.discard(item)
        s = min(s, buy * 0.95)
        self.sell_memo[item] = max(round(s, 2), A.SELL_FLOOR)
        return self.sell_memo[item]
```

becomes:

```python
    def sell(self, item):
        """Crafted item sell = sum of ingredient sells (arbitrage-free); root
        sell = rarity-scaled fraction of buy. Capped just under buy."""
        if item in self.sell_memo:
            return self.sell_memo[item]
        if item in T.CURRENCY_FACE:
            self.sell_memo[item] = float(T.CURRENCY_FACE[item])
            return self.sell_memo[item]
        buy = self.price(item)
        inputs = self.best_inputs.get(item)
        if not inputs or item in self.sell_stack:   # root or cycle
            s = T.root_sell(item, buy, self.cat_of.get(item))
        else:
            self.sell_stack.add(item)
            s = 0.0
            for who, n in inputs:
                s += self.sell(who) * n
            self.sell_stack.discard(item)
        s = min(s, buy * 0.95)
        self.sell_memo[item] = max(round(s, 2), T.SELL_FLOOR)
        return self.sell_memo[item]
```

- [ ] **Step 4: Verify the deriver runs and prices the reference items**

This requires `work/recipes.json` + `work/tags.json` to exist. If they don't, generate them first (only needed once / when jars change):

Run: `python tools/pricing/extract.py`
Expected: prints `recipes emitted: <N>` and `item tags: <N>` with no `MISSING jar` error. (If a jar path in `extract.py` is missing because mod versions changed, that is a pre-existing data-prep issue unrelated to this change — note it and proceed with whatever `work/*.json` already exists.)

Run: `python tools/pricing/derive.py`
Expected: report runs without a traceback. In the `=== sanity table ===` section, confirm headline magnitudes are sane, e.g.:
- `minecraft:cobblestone` buy `5`
- `minecraft:diamond` buy `4000`, via `anchor`
- `minecraft:diamond_block` buy ≈ `37800` (9 × diamond × 1.05, rounded), via a `recipe:` type
- `minecraft:nether_star` buy `700000`, via `anchor`

- [ ] **Step 5: Re-run the unit tests (no regression)**

Run: `python tools/pricing/test_tiers.py -v`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add tools/pricing/derive.py
git commit -m "feat(pricing): derive root buy/sell via tiers formula"
```

---

## Task 3: Update `audit.py` to report cells and the tuning worklist

**Files:**
- Modify: `tools/pricing/audit.py` (full rewrite — it's 31 lines)

The audit tool's job changes from "flag DEFAULT-priced items" to "show how each root resolved (exact / cell / rule / category-default) and its `(band, effort, rarity)` cell, and list the category-default roots as the worklist for adding rules."

- [ ] **Step 1: Rewrite `audit.py`**

Replace the entire contents of `tools/pricing/audit.py` with:

```python
"""Per-category QA for the effort×rarity pricer.
For each category, list how each root resolved (exact / cell / rule / category-default)
and its (band, effort, rarity) cell + computed buy. Category-default roots are the
worklist: they fell through to the crude per-tab fallback and likely want a CELL or RULE.
Usage:  python tools/pricing/audit.py [category]
"""
import json, sys, statistics
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
```

- [ ] **Step 2: Run the audit and confirm it works**

Run: `python tools/pricing/audit.py`
Expected: one block per category, each printing a median/max buy line, a count of category-default roots, and (for those) the item id, buy price, and resolved `[band eN rN]` cell. No traceback.

- [ ] **Step 3: Commit**

```bash
git add tools/pricing/audit.py
git commit -m "feat(pricing): audit reports cells and category-default worklist"
```

---

## Task 4: Delete `anchors.py`

**Files:**
- Delete: `tools/pricing/anchors.py`

- [ ] **Step 1: Confirm no `.py` file imports `anchors`**

Run: `git grep -n anchors -- tools/pricing`
Expected: zero matches (the only files in `tools/pricing` are `.py` scripts; docs live under `docs/` and are out of scope here). If `derive.py` or `audit.py` still references `anchors`, fix it before deleting.

- [ ] **Step 2: Delete the file**

```bash
git rm tools/pricing/anchors.py
```

- [ ] **Step 3: Re-run everything to confirm no breakage**

Run: `python tools/pricing/test_tiers.py -v`
Expected: PASS.

Run: `python tools/pricing/derive.py`
Expected: report runs without a traceback (no `No module named 'anchors'`).

- [ ] **Step 4: Commit**

```bash
git add -A tools/pricing
git commit -m "refactor(pricing): remove anchors.py (replaced by tiers.py)"
```

---

## Task 5: Calibrate against the audit worklist

This task is iterative tuning, not new code. The goal: every root resolves to an *intentional* cell, the reference items stay on target, and no category-default surprises remain.

- [ ] **Step 1: Generate the worklist**

Run: `python tools/pricing/audit.py > tools/pricing/work/audit-report.txt 2>&1`
Then open `tools/pricing/work/audit-report.txt` and review each category's `category-default roots` list.

- [ ] **Step 2: For each surprising default-classified root, add a CELL or RULE**

For any item in the DEFAULT list whose `[band eN rN]` cell looks wrong for what the item actually is, add either:
- a per-family `RULES` entry in `tiers.py` (preferred when it covers many ids — e.g. all `*_froglight`), or
- a single `CELL` entry (for a one-off root).

Re-run `python tools/pricing/audit.py <category>` after edits to confirm the item moved off the DEFAULT list with a sensible cell. Keep edits small and re-run frequently.

- [ ] **Step 3: Sanity-check the reference calibration table**

Run: `python tools/pricing/derive.py`
Confirm in the sanity table that the headline references are still in range (cobblestone 5, iron ingot ~480, diamond 4000, netherite items high, nether_star 700000). If a ladder factor needs nudging, change it once in `tiers.EFFORT` / `tiers.RARITY` / `tiers.BAND_BASE` and re-run the unit tests (`python tools/pricing/test_tiers.py -v`) — update the test expectations only if the calibration target itself intentionally changed.

- [ ] **Step 4: Verify no arbitrage / no absurd prices**

Eyeball the `=== price source ===` breakdown and sanity table from `derive.py`:
- recipe-priced count should dominate; `anchor` (formula/EXACT) should be the minority (roots only).
- No crafted item should be cheaper than its rawest ingredient (the `min`-over-recipes + EXACT-wins design prevents this; spot-check a few chains like ingot→block, planks→stairs).

- [ ] **Step 5: Commit the calibration**

```bash
git add tools/pricing/tiers.py tools/pricing/test_tiers.py
git commit -m "tune(pricing): calibrate tier cells against audit worklist"
```

- [ ] **Step 6 (optional, only when satisfied): write the new prices**

This overwrites `worth.json` (a backup `worth.json.bak2` is written automatically). Do this only after the report looks right.

Run: `python tools/pricing/derive.py --write`
Expected: `WROTE worth.json (<N> items). backup: worth.json.bak2`

Then in-game: `/eco reload`. Commit the regenerated price list:

```bash
git add worth.json
git commit -m "data(pricing): regenerate worth.json from effort×rarity model"
```

---

## Notes for the implementer

- **Run everything from the repo root**, not from `tools/pricing/` (the scripts resolve paths relative to their own location and expect the root as the working dir for `worth.json`).
- **`extract.py` is unchanged and only needs re-running when mod jars change.** If `work/recipes.json` / `work/tags.json` already exist, Tasks 2–5 work without re-extracting.
- **Don't reintroduce the `farm_tier` model.** Sell is purely rarity-scaled now; the suppression of infinite-farm income comes from the low *buy* price of trivial+abundant items, by design.
- **EXACT stays short.** If you're tempted to add many EXACT entries during calibration, prefer a RULE or CELL instead — EXACT is only for currency and true one-offs above the formula ceiling.
```
