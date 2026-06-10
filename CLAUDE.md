# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Emerald Economy is a **server-side-only** Fabric mod (Minecraft 1.21.11, Java 21) that adds a command/GUI economy where vanilla emeralds are the physical currency. No client mod is needed — everything renders on vanilla clients via the `sgui` server-GUI library. Read `README.md` for the player-facing economy rules (currency, whitelist pricing, fees, permissions); this file covers building and code architecture.

## Build & run

Use the Gradle wrapper. On Windows use `./gradlew` from the Bash tool or `.\gradlew.bat` from PowerShell.

```bash
./gradlew build            # compile + test + produce build/libs/emerald-economy-<version>.jar
./gradlew test             # JUnit 5 tests only
./gradlew runServer        # launch a dev Fabric server (working dir: run/)
./gradlew runDatagen       # run the Fabric data generator (EmeraldEconomyDataGenerator)
```

Run a single test class / method (fabric-loom uses the standard JUnit Platform):

```bash
./gradlew test --tests "savage.emeraldeconomy.economy.PriceBookTest"
./gradlew test --tests "savage.emeraldeconomy.rewards.DailyRewardsTest.someMethod"
```

- **JDK 21 is required** (`options.release = 21`). CI (`.github/workflows/build.yml`) runs `./gradlew build` on every push/PR.
- `run/` is the live dev-server directory (world, config, logs). `run/config/emerald-economy/` holds the runtime `config.json`, `worth.json`, and the `economy_data.sqlite` DB. The repo-root `worth.json` is the curated master price list edited by the Python tooling — distinct from the runtime copy.

## Dependencies are bundled (JAR-in-JAR)

`sgui`, `fabric-permissions-api`, `savdbcore`, and `common-economy-api` are shipped inside the jar via `include` in `build.gradle` — no separate downloads. **HikariCP and Caffeine are `compileOnly`**: they exist at runtime only because `savdbcore` bundles them. Don't add them as `include`/`modImplementation`; if you need them at compile time they're already `compileOnly`.

The access widener (`emerald-economy.accesswidener`) opens exactly one field: `ServerPlayerEntity.server`. Keep it minimal.

## Architecture

The mod is a singleton service core wrapped by thin command and GUI front-ends. All money operations funnel through one place.

- **`EmeraldEconomy`** (`onInitialize`) — the entrypoint. Registers the Common Economy API provider, all commands, server lifecycle hooks (load on `SERVER_STARTING`, save on `SERVER_STOPPING`), per-join account creation, disconnect settlement for open drop-GUIs, and the reward listeners.

- **`EconomyManager`** — the **central singleton** (`getInstance()`) holding all balance logic, config, the price book, and three Caffeine caches (account data, name→UUID, offline-names list). Almost every other class calls into it. Key invariants enforced here:
  - **Optimistic locking:** balance writes go through `addBalance`/`removeBalance`, which read an `AccountData` with a `version`, then call `storage.setBalance(uuid, newBalance, expectedVersion)` and retry (up to 10×, with backoff) on a version mismatch. Never write balances by bypassing this — `setBalance(uuid, amount)` (no version) is the unconditional admin path and invalidates cache.
  - **Money is never created or destroyed on failure.** `transfer` debits source then credits target, and **refunds the debit** if the credit fails (logging loudly). Mirror this debit-first/refund-on-failure discipline in any new money path.
  - Config loading normalizes nulls (`config`, `config.rewards`, `config.storage` are never null) and runs a one-time legacy-dir migration (`savs-common-economy` → `emerald-economy`).

- **`economy/TradeService`** — the **single buy/sell transaction path** shared by `/buy`, `/sell`, and the shop GUI. Always moves balance first, then items, so a storage failure never duplicates or loses goods. New trade surfaces should call this, not reimplement it.

- **`economy/PriceBook` + `config/WorthConfig` + `config/ItemPrice`** — pricing. `worth.json` is grouped by creative-tab category; `WorthConfig.flatten()` collapses it into a flat id→`ItemPrice` map that `PriceBook` serves. **Whitelist model:** an item is buyable only if it has a non-null `buy` price, sellable only with a non-null `sell` price; unlisted = not tradeable. Emerald (`minecraft:emerald`, `CURRENCY_ITEM_ID`) is always excluded — it *is* the currency. Prices reload live via `reloadPrices()` (`/eco reload`).

- **`storage/`** — `EconomyStorage` is the interface; `SqliteStorage` is the only impl (backed by `savdbcore`, table prefix from config). It owns accounts, the leaderboard query, and the transaction log table. `EconomyManager` caches in front of it.

- **`integration/`** — `EmeraldEconomyProvider` (+ `Account`, `Currency`) implements pb4's Common Economy API so other economy-aware mods can read/modify balances (provider id `emerald_economy`, currency `emerald_economy:dollar`). These adapt to the same `EconomyManager` balances.

- **`command/`** — one class per command group; each exposes a static `register(dispatcher)` matching the `EconomyCommand` functional interface, wired up in `EmeraldEconomy`. Commands are thin: parse args, call `EconomyManager`/`TradeService`, format output. `CommandSupport` holds shared helpers; permission checks go through `util/PermissionsHelper` (Fabric Permissions API with OP-level fallback).

- **`gui/`** — `sgui`-based chest-style screens. `ShopHubGui` is the `/shop` hub; the rest are leaf screens (Buy, Sell, Deposit, Withdraw, Transfer, TopBalances). **`ShopDropGuis` must settle on disconnect** (`settleOnDisconnect`, wired in `EmeraldEconomy`) because sgui's `onClose` doesn't fire on an abrupt disconnect — this is what guarantees dropped sell/deposit items are never lost.

- **`rewards/`** — new-player income: `DailyRewards` (`/daily`, cooldown-based, persisted), `MobKillRewards` (per-kill payout with an optional per-entity "tier list" override), `PlaytimeIncome` (session-based payout shown as a welcome-back message). All amounts/cooldowns live in `config.rewards` and are live-tunable via `/eco reload`.

- **`util/`** — `TransactionLogger` (writes the audit log queried by `/ecolog`), `PermissionsHelper`.

### Tests

`src/test/` covers **pure logic only** (no Minecraft runtime): `PriceBook`, `DailyRewards`, `PlaytimeIncome`, `DailyCommand`. Keep new unit tests on the side of logic that doesn't need a live server; anything touching registries/inventory/storage is exercised on the dev server instead.

## Pricing tooling (`tools/pricing/`)

Standalone Python (not part of the Gradle build) that derives buy/sell prices from the recipe graph. Workflow: in-game `/eco generateprices` seeds `worth.json` with price-less skeleton entries per creative tab → `extract.py` reads the mod jars into `work/recipes.json` (construction recipes), `work/conversions.json` (EVERY conversion incl. cutting/washing, for the arbitrage audit) and `work/tags.json` → `derive.py --write` computes prices and updates `worth.json` (with backup; report-only without `--write`) → **`arbitrage.py` must report 0** (it verifies no chain of in-game conversions turns shop-bought items into a larger sell value) → `/eco reload` in-game. `audit.py` lists per-category roots that fell through to crude category defaults. Root-item calibration lives in `tiers.py` (`EXACT`/`CELL`/`RULES` + `EXACT_SELL` for items whose payout must sit below a cheap replication chain); `test_tiers.py` (plain unittest, `python tools/pricing/test_tiers.py`) guards the known exploit pins. `derive.py` prunes reciprocal block↔unit recipe pairs and clamps every sell to 95% of the item's cheapest conversion-chain acquisition cost.
