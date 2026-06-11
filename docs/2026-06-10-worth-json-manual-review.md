# worth.json manual review — 2026-06-10

Scope: full gap review of the curated `worth.json` (3160 entries, 15 categories) plus the
interaction between its prices and in-game acquisition paths the automated audits don't see.
New review aids added under `tools/pricing/`: `gapscan.py` (structural checks) and
`villager_check.py` (villager-trade arbitrage table).

## Verified clean

- `arbitrage.py`: **0 / 4266** conversion chains profitable (re-run this session).
- `gapscan.py`: no duplicate ids across categories, no null/null skeletons, no zero/negative
  prices, every entry has both buy and sell.
- Currency handling: `minecraft:emerald` correctly absent; `emerald_block` at face value
  9/9 (identity, no profit); emerald ores priced so silk-touch/smelt/fortune cycles all lose.
- No creative-only / op items listed (no spawn eggs, command blocks, barrier, bedrock,
  spawners, budding amethyst, reinforced deepslate, infested blocks).
- High sell/buy ratios (diamond family 0.67–0.70, nether star, beacon) are all documented
  `EXACT`/`EXACT_SELL` pins in `tiers.py` — intentional mining-yield model, not gaps.
- tiers.py `netherite_upgrade_smithing_template` appearing twice is NOT a duplicate key:
  line 64 is the `EXACT_SELL` payout pin (20 000), line 94 the `EXACT` buy pin (120 000).
- **Piglin bartering is not a printer**: barter EV ≈ 250–260 per gold ingot (spectral
  arrows ~126, ender pearls ~52 dominate) vs gold ingot buy 480 — bartering bought gold
  loses money, and even selling the ingot directly (264) beats bartering it.

## Findings

### F1 — CRITICAL: villager trading is an unaudited currency faucet

`arbitrage.py` only audits crafting/conversion chains. But emeralds deposit at **1:1**
(`EconomyManager.depositEmeralds`), so every villager sell-to-player offer is a
currency→item conversion at *vanilla* emerald prices, while worth.json sells sit on the
effort×rarity scale — 1–3 orders of magnitude higher. `villager_check.py` finds
**46 of 47 sampled vanilla trades are money printers**, and villager trades restock daily:

| trade (emeralds → items) | payout | multiple |
|---|---|---|
| 2e → shears (sell 528) | 528 | **264x** |
| 3e → 3 golden carrot (247.2 ea) | 741.6 | **247x** |
| 1e → lantern (sell 243) | 243 | **243x** |
| 5e → ender pearl (sell 792) | 792 | **158x** |
| 5e → nautilus shell (sell 633.6) | 633.6 | **127x** |
| 4e → glowstone (sell 320) | 320 | **80x** |
| 3e → experience bottle (sell 192) | 192 | **64x** |
| 6e → saddle (sell 372) | 372 | **62x** |
| … 38 more ≥ 1.2x … | | |

Worse, master armorers/toolsmiths sell **enchanted diamond gear** for ~13–30 emeralds:
a ~20-emerald enchanted diamond chestplate sells to the shop for **22 400 (~1000x)**,
because `TradeService` matches by item id only (see F2).

This cannot be closed by tweaking individual sell prices without flattening the whole
price model (you'd have to crush lantern/pearl/shears/diamond-gear sells to single
digits). Realistic options, pick one deliberately:

1. **Server-side rule**: block or tax villager trading (or just the sell-to-player side),
   e.g. a mixin/event that cancels `MerchantScreenHandler` purchases — keeps worth.json
   coherent.
2. **Sell-side caps** (`EXACT_SELL`) for the worst offenders only (pearl, shears, lantern,
   golden carrot, glistering melon, nautilus shell, glowstone, xp bottle, saddle, shield,
   flint and steel, diamond armor/tools) — accepts small printers, kills the big ones.
   Mirrors the existing quartz/andesite-alloy precedent.
3. **Accept and monitor** via `/ecolog` — fastest but the faucet is fully automatable
   (villager crop trading prints the emeralds too).

Either way, `villager_check.py` should join `arbitrage.py` in the release checklist.

### F2 — HIGH: TradeService sells by item id only (enchantments/damage ignored)

`TradeService` line 68/80: `s.getItem() == item`. Consequences:

- Enchanted gear sells at the plain-item price → combined with F1's armorer trades it's
  the ~1000x printer above; also raid/fishing loot gear sells at full price.
- Nearly-broken tools sell at the same price as fresh ones (craft-use-sell is free wear).
- Buy side of component-carrying items is junk: `minecraft:enchanted_book` (buy 480)
  vends a book with **no stored enchantment**; same for potions/tipped arrows if bought.

Suggested: refuse (or steeply discount) damaged/enchanted stacks on sell, and consider
delisting buy on `enchanted_book`/potion-family items that are meaningless without
components. (Code change — out of scope for this data review.)

### F3 — MEDIUM: two craftable vanilla items were never seeded into worth.json

`gapscan.py` cross-checks recipe/conversion outputs against worth.json:

- `minecraft:creaking_heart` — craftable (appears in `work/recipes.json`), never listed.
- `minecraft:dirt_path` — appears as a conversion output, never listed.

Never present in any worth.json revision (`git log -S` finds nothing), so the in-game
`/eco generateprices` seed skipped them (not in the creative tabs it scanned, or added
by a game update after seeding). Unlisted = untradeable, so this is safe but a coverage
gap: re-run `/eco generateprices` on the current game version, then `derive.py --write`.

Non-issues from the same check: 6 infested blocks (creative-only, correctly unlisted) and
`silentgear:netherwood_log`/`stripped_netherwood_log` (stray compat recipe in the graph;
no silentgear items exist anywhere in worth.json — confirm the mod isn't installed and
ignore).

### F4 — LOW (design): farmable mob-drop sells worth a deliberate look

Same spirit as the fixed wither-skull printer, but unbounded-grind rather than bounded
arbitrage, so possibly accepted by design:

- `totem_of_undying` sell 8 250 — raid farms produce dozens/hour.
- `ender_pearl` sell 792 — enderman farms produce stacks/hour.
- `glowstone_dust` sell 80 — witch farms.
- `experience_bottle` sell 192 — also a 64x cleric trade (F1).

### F5 — INFO: residual audit worklist

`audit.py` still reports ~36 category-default roots (27 biomesoplenty technical/easter-egg
blocks at buy 12, 13 farmersdelight wilds at 24, 7 uncategorized incl. `create:schematic`
and `create:shopping_list` at 75, 1 furniture hedge). All low-value; the BOP set includes
unobtainable technical blocks (`null_block`, `anomaly`, `origin_grass_block`) that could
simply be delisted instead of priced.

## Suggested order of work

1. Decide F1 policy (server rule vs EXACT_SELL caps) — everything else is noise next to it.
2. Fix F2 sell-side matching (blocks the F1 enchanted-gear variant even if trades stay).
3. Reseed + rederive for F3; delist BOP technical blocks while in there (F5).
4. Revisit F4 once F1/F2 are settled.
