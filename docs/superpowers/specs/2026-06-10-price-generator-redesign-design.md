# Price Generator Redesign — Design

**Date:** 2026-06-10
**Status:** Approved design, ready for implementation planning
**Scope:** `tools/pricing/` (Python tooling). No mod (Java) changes.

## Problem

The current generator prices crafted items well (recipe graph, arbitrage-free), but
its **foundation is guessed**. Root items — anything no construction recipe produces —
get their value from `anchors.py`: a ~200-entry `EXACT` table, a long ordered list of
regex `RULES`, and per-category fallbacks. Every value is a hand-picked number. Two
consequences:

1. **Bad roots poison the tree.** A wrong raw-material anchor propagates into every
   item crafted from it.
2. **No grounding in reality.** Prices reflect recipe *structure*, not how hard an
   item is to *obtain*. The sell side compounds this: today's `farm_tier` model makes
   value a function of *automatability* — infinite farms are cheap because they're
   farmable, which is exactly the wrong mental model.

## Goal

Ground root prices in **effort (how hard to obtain) + rarity (how scarce in-world)**,
expressed through a small, principled, bounded set of knobs — replacing the sprawling
hand-tuned anchor table. Keep the recipe-graph derivation for crafted items untouched.

Explicitly **drop the "farmability" axis**: how automatable an item is must not set its
price. The infinite-farm problem dissolves on its own, because trivial+abundant items
land at a tiny buy price regardless of how farmable they are.

## Model

### Three pricing paths

| item kind | how it's priced |
|---|---|
| **root** (no construction recipe) | the formula below |
| **crafted** (has a construction recipe) | recipe graph — *unchanged* |
| **one-off uniques** (~15-20 items) | sparse `EXACT` override (wins over everything) |

### Root buy formula

```
root_buy = band_base × effort_factor × rarity_factor
```

Three inputs, each assigned by **layered classification** (see below):

- **band** → `band_base` (the price band the item belongs to)
- **effort** → `effort_factor` (how hard to obtain)
- **rarity** → `rarity_factor` (how scarce in the world)

**Decision (Way A): effort × rarity carry the price magnitude; `band_base` is a small
offset.** `band_base` only ever applies to *roots* (raw mats, mob drops, natural blocks,
plants). The "categories cost different amounts" effect (combat ≈ 300, food ≈ 25) lives
in *crafted* items, which the recipe graph already prices for free from their ingredients
— so big per-category bases are unnecessary and would compress the ladder range. For the
items `band_base` actually governs, effort + rarity *are* the real differentiator
(dirt vs diamond = trivial+abundant vs hard+very-rare). So the ladders are the big lever
and `band_base` just separates two items sitting in the same cell (a bone vs a pebble).

#### Effort ladder (5 levels, shared globally)

| level | meaning | factor |
|---|---|---|
| 1 | trivial — any tool, or just pick it up | ×1 |
| 2 | easy — basic mining / farming | ×3 |
| 3 | moderate — travel, specific tool, or dimension access | ×8 |
| 4 | hard — dangerous / gated (diamond depth, ancient debris) | ×20 |
| 5 | extreme — boss / raid / rare-structure acquisition | ×40 |

#### Rarity ladder (5 levels, shared globally)

| level | meaning | factor |
|---|---|---|
| 1 | abundant — everywhere | ×1 |
| 2 | common | ×2 |
| 3 | uncommon | ×5 |
| 4 | rare | ×12 |
| 5 | very rare / near-unique | ×40 |

#### Band base (per category, NOT per-item)

`band_base` is one number per **price band**. Bands are derived from creative-tab
categories but can be **finer** (see "Bands vs categories"). The band sets where a class
of root items sits; effort × rarity position the item *within* its band.

> **The effort and rarity ladders are global.** Only `band_base` varies between
> categories. This is the entire reason the knob budget stays small: ~8 band bases +
> 10 ladder factors + ~15-20 `EXACT` overrides ≈ the whole tuning surface, versus
> hundreds of hand-set numbers today.

#### Range and calibration

With a global-style `band_base ≈ 5`, the formula spans `5 × 1×1 = 5` →
`5 × 40×40 = 8000`. Anything that must price above ~8000 (nether star, dragon egg,
elytra, totem, netherite ingot, smithing templates) is an `EXACT` override, not a
formula output.

The factor values above are **starting points**. During implementation they are
**calibrated** against a small set of reference anchors so magnitudes land sensibly:

| item | band·base | effort | rarity | `= base×E×R` | buy | (old anchor) |
|---|---|---|---|---|---|---|
| cobblestone | raw_stone·5 | 1 (×1) | 1 (×1) | 5×1×1 | **5** | 5 |
| coal | raw_metal·5 | 2 (×3) | 2 (×2) | 5×3×2 | **30** | 40 |
| copper ingot | raw_metal·5 | 3 (×8) | 3 (×5) | 5×8×5 | **200** | 200 |
| iron ingot | raw_metal·5 | 3 (×8) | 4 (×12) | 5×8×12 | **480** | 400 |
| gold ingot | raw_metal·5 | 3 (×8) | 4 (×12) | 5×8×12 | **480** | 500 |
| diamond | raw_gem·5 | 4 (×20) | 5 (×40) | 5×20×40 | **4000** | 4000 |
| ancient_debris | raw_gem·5 | 4 (×20) | 5 (×40) | 5×20×40 | **4000** | 4000 |
| ender_pearl | mob_drop·6 | 4 (×20) | 4 (×12) | 6×20×12 | **1440** | 1200 |
| nether_star | — | — | — | `EXACT` | **700000** | 700000 |

Calibration means: pick `band_base` and ladder factors so these reference cells produce
prices in the target neighborhood, then let everything else fall out of its cell. The
values above are the agreed starting point.

### Crafted buy price — unchanged

```
crafted_buy = min over recipes of ( Σ input_cost × MARKUP )      # MARKUP = 1.05
```

Root/`EXACT` values still **win** over recipe-derived values for the same item, so
decomposition/processing recipes can't undervalue a raw material. Oxidation/wax copper
variants continue to inherit the plain form's value (`base_copper`).

### Sell price — rarity-scaled

```
root_sell    = root_buy × sell_fraction[rarity]
crafted_sell = Σ ( ingredient_sell )          # arbitrage-free; capped just under buy
```

| rarity | 1 abundant | 2 common | 3 uncommon | 4 rare | 5 very rare |
|---|---|---|---|---|---|
| sell_fraction | 25% | 30% | 40% | 55% | 70% |

Crafted items stay arbitrage-free: their sell is the sum of ingredient sells (so you
can never craft-up and profit on the sell-back), capped just below buy, floored at
`SELL_FLOOR`. The old `farm_tier` model is **removed entirely**.

Currency-equivalent items keep face-value sell (`emerald_block` = 9), unchanged.

## Bands vs categories (the key decoupling)

`worth.json` categories are **auto-generated** from creative tabs by
`/eco generateprices` and regenerate for free as mods change. We do **not** hand-recategorize
the file — that would create a permanent manual mapping to maintain on every mod update.

Instead, the **price band is a separate concept** assigned by the classifier, and may be
finer than the creative-tab category:

```
band = first matching band-rule, else the item's creative-tab default band
```

- `worth.json` category stays = creative tab. Untouched. Still drives shop-GUI grouping.
- The pricing classifier maps roots to finer bands via rules (e.g.
  `#c:raw_materials` / `*_ore` → `raw_metal`; mob-drop tags → `mob_drop`;
  raw stone/earth → `raw_stone`). Unmatched items fall back to a per-creative-tab
  default band.

This gives finer, more meaningful bands while keeping the file auto-regenerating, and
puts band assignment in the same one place where effort/rarity are tuned.

### Starting band set (calibratable)

A small set of root-bearing bands, since most roots are raw mats / natural blocks /
mob drops:

| band | example members | base |
|---|---|---|
| `raw_stone` | cobblestone, dirt, sand, gravel, netherrack | **5** |
| `raw_metal` | iron/gold/copper ingots & ores, redstone, coal, quartz | **5** |
| `raw_gem` | diamond, amethyst, lapis, ancient_debris | **5** |
| `mob_drop` | bone, string, leather, blaze rod, ender pearl | **6** |
| `plant_produce` | wheat, crops, saplings, kelp | **4** |
| `natural_block` | misc natural/terrain blocks not raw mats | **6** |
| `treasure` | structure/loot roots not covered by `EXACT` | **12** |
| `default` | per-creative-tab fallback for anything unmatched | **5** |

Genuinely unique items (nether star, templates, etc.) bypass bands via `EXACT`.

## Layered classification (how a root gets band/effort/rarity)

Same proven 3-tier resolution that already covers the whole item set, now emitting
**cells** (band, effort, rarity) instead of free-floating prices:

1. **`EXACT[id]`** — sparse per-item override. May supply a final price directly (true
   one-offs) OR an explicit (band, effort, rarity) cell. ~15-20 entries.
2. **`RULES`** — ordered regex / tag rules on the item id → a cell. Handles families in
   bulk (`*_log`, `*_ore`, mob-drop tags, flowers, etc.). First match wins.
3. **Per-creative-tab default** — the safety net so nothing is unclassified; maps the
   item's worth.json category → a default (band, effort, rarity).

An item priced by the recipe graph never touches this — classification only fires for
roots and for `EXACT`-protected raw materials.

## Architecture & files

```
tools/pricing/
  extract.py   UNCHANGED — recipes + tags from jars → work/recipes.json, work/tags.json
  tiers.py     NEW — replaces anchors.py. Ladders, band bases, sell fractions,
               classification rules (EXACT / RULES / category defaults), and the
               functions: classify(id, category) -> (band, effort, rarity);
               root_buy(...); root_sell(...).
  derive.py    MODIFIED — root pricing path calls tiers.root_buy / tiers.root_sell
               instead of anchors.anchor_price / farm-tier. Recipe recursion, EXACT-wins,
               base_copper, round_buy, --write all unchanged.
  audit.py     MODIFIED — becomes the QA surface: lists each root's resolved
               (band, effort, rarity) and price source (exact / rule / default), and
               flags every item that fell through to a category default (the
               maintenance worklist: "these need a band-rule").
```

`anchors.py` is removed; its useful raw classifications (which items are which tier) are
migrated into `tiers.py`'s rules. The runtime mod, `worth.json` structure, and the
in-game `/eco generateprices` → `extract` → `derive --write` → `/eco reload` workflow
are unchanged.

## Verification / workflow

No Java test changes (the Python tooling has no unit tests today; mod-side tests are
unaffected). Verification is via the report tooling:

1. `derive.py` (report mode) prints the **calibration table** — reference items with
   their resolved cell and computed buy/sell — to confirm magnitudes are sane.
2. `derive.py` prints a **price-source breakdown** (how many items priced by recipe vs
   exact vs rule vs category-default).
3. `audit.py` lists **all category-default-classified roots** as the explicit worklist
   for adding band/tier rules, plus sell<buy and outlier sanity checks.

A redesign is "done" when: every root resolves to an intentional cell (no surprises in
the audit default list beyond accepted ones), the reference calibration table lands in
target neighborhoods, and no arbitrage (crafted sell ≤ sum of parts ≤ buy) holds.

## Out of scope (YAGNI)

- Data-derived rarity (worldgen/loot-table parsing) — rejected in favor of bounded
  hand-classified tiers.
- Per-category effort/rarity ladders — rejected; ladders stay global, only band base
  varies.
- Recategorizing `worth.json` — rejected; bands are a separate classifier concept.
- Any mod (Java) changes.
```
