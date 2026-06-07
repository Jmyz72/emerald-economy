# Design: Savs Common Economy → Dedicated Server Command Economy

**Date:** 2026-06-08
**Status:** Approved design, pending spec review
**Target:** Minecraft 1.21.11, Fabric (loader 0.19.3, Java 21), single dedicated server

## Goal

Transform the existing third-party "Savs Common Economy" mod into a customized
build dedicated to one server. The core change is conceptual: replace the
**physical chest-shop system** with a **server-wide command economy** where
every item has a price and any player can buy or sell any item from anywhere
via commands. Deployment-specific toggles are removed and hardcoded for this
server.

This is a customization of the existing mod (package id `savs-common-economy`,
group `savage.commoneconomy` unchanged) — not a rebrand.

## Decisions (locked)

1. **Trade model:** server-wide price list; buy/sell any item via commands from
   anywhere. No physical shops.
2. **Pricing:** every item has a **separate buy price and sell price**.
3. **Price source:** curated per-item list **+ global fallback** buy/sell price
   for any item not in the list.
4. **Unbuyable blacklist:** dangerous/unobtainable items (bedrock, spawners,
   command blocks, barriers, light blocks, etc.) cannot be **bought**, but may
   still be **sold**.
5. **Storage:** SQLite only. JSON, MySQL, PostgreSQL, and Redis are removed.
6. **Server topology:** single Minecraft server (no proxy network, no cross-
   server sync).
7. **Feature toggles removed and hardcoded:**
   - sell/buy commands: always enabled
   - chest shops: removed entirely
   - `symbolBeforeAmount`: hardcoded to "before" (`$100`)
   - notification feedback: hardcoded to **CHAT**
   - all Redis settings: removed
   - all MySQL/Postgres connection fields: removed
8. **Physical currency = vanilla emeralds (1:1):**
   - `/withdraw <amount>` gives **vanilla emeralds** (not paper notes), 1 emerald = $1, no fee.
   - New `/deposit <amount>` and `/deposit all` convert emeralds back to balance,
     minus a **20% deposit fee** (`depositFeePercent`, default 20). Withdraw stays fee-free.
   - The old **right-click bank-note redemption is removed** entirely (no paper notes).
   - `minecraft:emerald` is a **pure currency item**: excluded from both `/buy` and
     `/sell` so it can only move via `/withdraw` / `/deposit` (prevents the
     fallback-price arbitrage where a $1 emerald sells for the $5 fallback).
9. **Economy-balance guidance (for the curated price list, not code):** keep
   `sell ≈ 50–60% of buy` as the core money sink; audit prices so no
   craft/smelt loop has `sell(output) > buy(inputs)`. Emerald farming is an
   accepted income source, throttled by the 20% deposit fee.

## Scope of Changes

### Files to DELETE

Shop system (entire `shop` package):
- `shop/Shop.java`
- `shop/ShopManager.java`
- `shop/ShopCommands.java`
- `shop/ShopInteractionManager.java`
- `shop/ShopSignHelper.java`
- `shop/ShopStockCalculator.java`
- `shop/ShopTransactionHandler.java`
- `shop/ShopTransactionManager.java`
- `shop/ShopType.java`

Storage backends not used:
- `storage/JsonStorage.java`
- `storage/MysqlStorage.java`
- `storage/PostgresStorage.java`

Redis (entire multi-server sync layer):
- `util/RedisManager.java`
- `util/RedisBackend.java`
- `util/RealRedisBackend.java`

### Files to KEEP unchanged (or near-unchanged)

- `storage/EconomyStorage.java` (interface)
- `storage/SqlStorage.java` (base)
- `storage/SqliteStorage.java` (the one backend)
- `integration/SavsEconomyProvider.java`, `SavsEconomyAccount.java`,
  `SavsEconomyCurrency.java` (Common Economy API integration)
- `command/LogCommand.java` (`/ecolog`)
- `command/DebugCommands.java` (`/ecodebug`)
- `util/TransactionLogger.java`
- `util/PermissionsHelper.java`

### Files to MODIFY

**`SavsCommonEconomy.java`** (main initializer)
- Remove all shop event handlers: `UseBlockCallback` (sign click),
  `ServerMessageEvents.ALLOW_CHAT_MESSAGE` (amount entry),
  `PlayerBlockBreakEvents.BEFORE` (chest/sign protection),
  `ServerTickEvents.END_SERVER_TICK` (orphan cleanup + sign updates).
- Remove `ShopManager` load/save in server start/stop lifecycle.
- Remove Redis initialization in `SERVER_STARTING`.
- Remove the `enableChestShops` branches; always register buy/sell commands.
- **Remove** the bank-note `UseItemCallback` right-click redemption entirely.
- Keep: Common Economy provider registration, account-on-join, `EconomyManager`
  load/save lifecycle.
- Register the new `/buy` and `/deposit` commands (and keep `/sell`, `/worth`,
  `/withdraw`).

**`config/EconomyConfig.java`**
- Remove: `symbolBeforeAmount`, `enableSellCommands`, `enableChestShops`,
  the entire `RedisConfig`, and the MySQL/Postgres fields of `StorageConfig`
  (`host`, `port`, `database`, `user`, `password`, `tablePrefix`, `poolSize`,
  `connectionTimeout`, `idleTimeout` — keep only what SQLite needs, e.g. file
  name).
- Remove `NotificationMode` config fields (`apiNotificationMode`,
  `commandNotificationMode`); behavior hardcoded to CHAT.
- Remove the `StorageType` enum (only SQLite remains) or pin it to SQLITE.
- Keep: `defaultBalance` (1000), `currencySymbol` (`$`).
- Add: `defaultBuyPrice` (fallback, e.g. 10), `defaultSellPrice` (fallback,
  e.g. 5), `depositFeePercent` (default 20).

**`config/WorthConfig.java`**
- Change `itemPrices` from `Map<String, BigDecimal>` (single price) to a map of
  item id → `{ buy, sell }` price pair. Introduce a small `ItemPrice` holder
  (buy + sell `BigDecimal`).
- Add `unbuyable` — a `List<String>`/`Set<String>` of item ids that cannot be
  bought (still sellable).
- Seed sensible defaults for both the curated list and the blacklist.

**`EconomyManager.java`**
- Remove all Redis publish/init calls and Redis imports.
- Remove JSON/MySQL/Postgres storage selection; always construct
  `SqliteStorage`.
- Replace single-price `getItemPrice(itemId)` with `getBuyPrice(itemId)` and
  `getSellPrice(itemId)`, applying the fallback default when an item is not in
  the curated list.
- Add `isBuyable(itemId)` honoring the unbuyable blacklist.
- Add `isCurrencyItem(itemId)` → true for `minecraft:emerald`; the trade
  commands reject it so it cannot be bought or sold (currency-only).
- Keep balance operations, account management, formatting (symbol always
  before amount), top-accounts.

**`command/EconomyCommands.java` — `/withdraw` change**
- `/withdraw <amount>` now removes `amount` from balance and gives the player
  `amount` **vanilla emeralds** (1:1), no NBT, split across stacks via
  `offerOrDrop`. Remove the paper-note `ItemStack`/NBT creation. No fee.

**`command/SellCommands.java`**
- Keep `/sell`, `/sell all` (now using `getSellPrice`).
- Extend `/worth`, `/worth <item>`, `/worth all`, `/worth list` to show **both**
  buy and sell prices.
- Remove the `isSellEnabled()` guard (always registered).
- Hardcode feedback to CHAT (`sendFeedback`), not the removed notification mode.

**`command/EconomyCommands.java`**
- Remove all Redis `publishTransaction` calls and the `sendCommandFeedback`
  notification-mode branching; send feedback directly to chat.
- Keep all balance/admin commands and `/withdraw` bank-note logic.

### Files to ADD

**`command/BuyCommand.java`** (or fold into `SellCommands`)
- `/buy <item> <amount>`:
  - Validate the item exists and is not on the `unbuyable` blacklist.
  - Compute cost = `getBuyPrice(item)` × amount.
  - Reject `minecraft:emerald` (currency item, not tradeable).
  - If player can afford it: deduct balance, give items via
    `inventory.offerOrDrop`, log via `TransactionLogger`, send chat feedback.
  - Item arg uses a suggestion provider listing buyable priced items.
  - Permission: `savscommoneconomy.command.buy` (default allow).

**`command/DepositCommand.java`**
- `/deposit <amount>` and `/deposit all`:
  - Count emeralds (`minecraft:emerald`) in the player's inventory.
  - `<amount>` deposits up to that many; `all` deposits every emerald held.
  - Remove the emeralds from inventory; credit
    `floor-safe` balance = `emeralds × (1 − depositFeePercent/100)`
    (e.g. 100 emeralds at 20% → +$80).
  - Log via `TransactionLogger`, send chat feedback showing emeralds deposited,
    fee taken, and net credited.
  - Permission: `savscommoneconomy.command.deposit` (default allow).
- Note: `/sell` must also reject `minecraft:emerald` (currency item).

## Final Command Set

Kept / modified:
- `/bal`, `/balance [target]` — check balance
- `/baltop`, `/balancetop` — top 10
- `/pay <target> <amount>`
- `/withdraw <amount>` — now gives **vanilla emeralds** 1:1 (no fee)
- `/givemoney`, `/takemoney`, `/setmoney`, `/resetmoney` (admin, OP 2)
- `/sell`, `/sell all` (rejects emerald)
- `/worth`, `/worth all`, `/worth list`, `/worth <item>` (shows buy + sell)
- `/ecolog <target> <time> <unit> [page]` (admin)
- `/ecodebug verify|cleanup|api` (owner)

New:
- `/buy <item> <amount>` (rejects emerald + blacklisted items)
- `/deposit <amount>` / `/deposit all` — emeralds → balance, minus 20% fee

Removed:
- All `/shop ...` commands
- Right-click bank-note redemption

## Config Shape (after change)

```
defaultBalance    = 1000
currencySymbol    = "$"          // always rendered before the amount
defaultBuyPrice   = 10           // fallback for unlisted items
defaultSellPrice  = 5            // fallback for unlisted items
depositFeePercent = 20           // % burned when depositing emeralds
storage           = { tablePrefix, poolSize, connectionTimeout, idleTimeout }
                                 // SQLite only; db filename fixed in savdbcore
itemPrices        = {
  "minecraft:diamond": { buy: 100, sell: 60 },
  ...
}
unbuyable         = [
  "minecraft:bedrock", "minecraft:spawner", "minecraft:command_block",
  "minecraft:barrier", "minecraft:light", ...
]
// minecraft:emerald is currency: hardcoded-excluded from /buy and /sell,
// not stored in itemPrices.
```

## Error Handling

- `/buy` of a blacklisted item → error: item cannot be bought.
- `/buy` or `/sell` of `minecraft:emerald` → error: it's currency, use
  `/withdraw` / `/deposit`.
- `/buy`/`/sell` of an item with no price and no fallback applicable → handled
  by fallback, so always priced; sell of a zero/negative-priced item rejected.
- Insufficient funds on `/buy` or `/pay` → error, no state change.
- `/deposit <amount>` with fewer emeralds than requested → deposit only what's
  held (or error if zero emeralds).
- `/withdraw <amount>` with insufficient balance → error, no emeralds given.
- Unknown player target on admin/pay commands → existing "player not found"
  error path retained.
- Inventory full on `/buy` or `/withdraw` → items/emeralds drop at player feet
  (`offerOrDrop`).

## Testing / Verification

- Manual in-game smoke test on a dev server: buy listed item, buy fallback-
  priced item, attempt to buy blacklisted item (rejected), attempt to buy/sell
  an emerald (rejected), sell items, check `/worth` shows both prices,
  `/withdraw` gives emeralds 1:1, `/deposit all` credits balance minus 20%,
  admin money commands.
- `/ecodebug verify` still passes (concurrency/consistency on SQLite).
- Confirm SQLite file is created and balances persist across restart.
- Confirm no references remain to deleted shop/Redis/other-storage classes
  (project compiles).

## Out of Scope

- Rebranding (mod id, package, name, authors).
- GUI/menu-based shop interface.
- Per-player or dynamic pricing, stock limits, market fluctuation.
- Multi-server / proxy support.
```
