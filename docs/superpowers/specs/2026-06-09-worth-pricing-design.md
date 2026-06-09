# Emerald Economy — `worth.json` Pricing Design

**Date:** 2026-06-09
**Scope:** Assign `buy`/`sell` prices to every survival-obtainable item in `worth.json`
(~3160 items across 15 categories, 4 mods + vanilla).

---

## 1. Context & constraints

- Currency is **emeralds**. `worth.json` stores per-item `{ "buy": n, "sell": n }`
  as `BigDecimal` (decimals allowed). Either field may be `null`
  (null buy = not buyable, null sell = not sellable). Unlisted items are untradeable.
- Pricing is **purely static** — the mod has **no** dynamic pricing, sell caps, taxes,
  or diminishing returns. The only levers are the numbers themselves and whether an
  item is listed.
- The server already has a massive emerald **faucet**: an auto-trader mod + emerald
  farm can produce **~100,000 emeralds/day** per player, AFK. We are **not** changing
  the mod's systems — only setting prices.

**Consequence:** The economy is a **sink problem, not a faucet problem.** Players will
have emeralds regardless of what we do; the design makes those emeralds *meaningful to
spend* and prevents selling from becoming a second runaway faucet.

## 2. Pre-step (DONE) — remove non-survival items

Removed 187 creative/op/non-obtainable entries before pricing: the whole `op_blocks`
and `spawn_eggs` categories, vanilla creative-only blocks (bedrock, budding_amethyst,
infested blocks, end_portal_frame, vault, reinforced_deepslate, etc.), Create creative
items, all BOP `potted_*` + block-only plant/strand forms + `bop_icon`, BOP fluids,
Create transient/rare-package items, and `dirt_path`/`farmland`. Result: **3160 items,
15 categories.** Original backed up at `worth.json.bak`.

## 3. Two decoupled price scales

`buy` and `sell` are set **independently**. This is the core of the design — scaling
buy up must NOT scale sell up, or high buy prices would turn farms into printers.

### 3a. BUY = the sink (high, recipe-derived)

Calibrated to the ~100k/day income so a **big-ticket item takes a week+** to afford.

**Value-tier anchors (raw/root items, hand-set):**

| Tier | Examples | Buy (emeralds) |
|---|---|---|
| T0 Bulk | dirt, cobblestone, gravel, sand | 5 |
| T1 Common | log 50, stone 30, coal 40, wheat 40 | 13–50 |
| T2 Basic resource | **iron 400**, copper 200, redstone 60, leather 150 | 60–400 |
| T3 Valuable | gold 500, amethyst 300, quartz 200, blaze rod 800 | 200–800 |
| T4 Rare | **diamond 4000**, ender pearl 1200, obsidian 400 | 1.2k–4k |
| T5 Very rare | netherite scrap 4k, totem 15k, heart of sea 12k | 4k–15k |
| T6 Trophy | **netherite ingot 20k**, ench. golden apple 80k, beacon 150k, nether star 700k, dragon egg 1M | 20k–1M |

**Crafted items are recipe-derived**, not guessed:
`buy(item) = min over recipes of ( Σ buy(ingredient) × qty ) × markup`.
Multi-step chains resolve down to roots (iron door → 6 iron → 2400; planks → ¼ log).
Markup ≈ 1.0–1.1 (small, so crafted ≥ parts — no craft-arbitrage). Items reachable by
no recipe are **roots** and must have a hand-set anchor (raw ores, gems, mob drops,
crops, mod base resources).

### 3b. SELL = minor convenience (low, decoupled)

Selling is NOT a primary income (the emerald farm is). Rules:

- **Rare / non-farmable (F0):** `sell = buy × ~2%` — safe because throughput is naturally
  limited (you can't AFK-farm diamonds). diamond → 80, netherite → 400.
- **Farmable (F1/F2/F3):** `sell = low flat ABSOLUTE value`, anchored to realistic farm
  *throughput* so a 10k/hr farm earns **less** than the emerald farm — NOT a % of the
  high buy price. iron ≈ 0.5, gold ≈ 1, cobble ≈ 0.02, mob drops ≈ 0.05.

This gives new players a small starter income from early ores while making industrial
farms economically pointless to sell from.

## 4. Farm-tier classification (drives sell)

| Farm tier | Definition | Examples | Sell rule |
|---|---|---|---|
| F0 Rare/manual | Can't be AFK-automated | diamond, netherite, ancient debris, exploration loot | 2% of buy |
| F1 Renewable, active | Needs player effort | hand-harvested wood, manual crops | low flat (mid) |
| F2 AFK-automatable | Iron/gold/mob/Create farms, auto crops | iron, gold, mob drops, Create output | low flat (small) |
| F3 Trivially infinite | Endless, zero effort | cobble, dirt, rotten flesh, string | flat floor ~0.02 |

Everything stays **sellable** (no sell-disabling) — balance comes from low farmable sell
values, not from removing the option.

## 5. Derivation engine (the build)

A standalone offline script (Python) that produces the filled `worth.json`. It does NOT
run inside the mod.

**Inputs (recipe + tag JSONs read from jars):**
- Vanilla 1.21.11 (recipe source pinned at build time; 1.21.6 fallback ≈ identical).
- `create-fly-1.21.11-6.0.8-3.jar`, `FarmersDelight-…jar`,
  `BiomesOPlenty-…jar`, `refurbished_furniture-…jar`
  from `C:\Users\carso\AppData\Roaming\.minecraft\mods`.

**Pipeline:**
1. **Parse recipes** → map `outputItem → [recipe{inputs[], qty, type}]`. Handle schemas:
   vanilla `crafting_shaped/shapeless/stonecutting/smelting`; Create `crushing/milling/
   mixing/deploying/cutting/sequenced_assembly`; FD `cutting/cooking`; Refurbished
   `workbench_constructing/cutting_board_*`. Processing recipes treated as 1-step
   ingredient costs (e.g. crushed_raw_iron = raw_iron).
2. **Resolve tags** (`#minecraft:planks`, `#c:iron_ingots`) from tag JSONs → pick the
   cheapest concrete member.
3. **Anchor table** — hand-authored root prices (vanilla value tiers + mod base
   resources like Create zinc/brass sources, FD mud, BOP gems).
4. **Resolve buy** — memoized DFS, `min` over recipes, cycle detection; markup applied.
   Unreachable non-anchored items → flagged for manual/flat-tier assignment.
5. **Classify farm tier** — by item id / category / keyword lists → compute sell.
6. **Emit** `worth.json` preserving category structure & key order; back up first.

**Output:** review **category by category** with the user, starting with vanilla
resources to sanity-check anchors before touching the ~1400 modded items.

## 6. Out of scope (possible later)

- Any code-level mechanism (daily sell cap, dynamic price decay). Explicitly declined —
  prices-only. Noted as a future option since the source is owned.
- Pricing the auto-trader mod's own items beyond what's already listed.

## 7. Success criteria

- Every one of the 3160 items has a deliberate `buy` and `sell` (or justified `null`).
- No craft-arbitrage: `buy(crafted) ≥ Σ buy(ingredients)` everywhere.
- No sell-arbitrage: `sell(item) < buy(item)` everywhere.
- A big-ticket T6 item costs ~700k–1M (≈ a week+ at 100k/day).
- Farmable items' sell value × realistic farm throughput < emerald-farm income.
- `worth.json` is valid JSON, loads via `/eco reload`, category/key order preserved.
