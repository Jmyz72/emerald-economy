# Whitelist Pricing + Shop GUI — Design

Date: 2026-06-09
Status: Approved (pending spec review)

## Goal

Two coupled changes to the Savs Common Economy mod:

1. **Whitelist pricing** — remove the global fallback price and the `unbuyable`
   blacklist. An item is tradeable only if `worth.json` gives it an explicit
   price. A buy price makes it buyable; a sell price makes it sellable; the two
   are independent. Nothing is priced by default.
2. **Shop system** — a server-side sgui hub (`/shop`) whose slots are
   item-buttons linking to Buy, Sell, Deposit, Withdraw, Transfer, and Top
   Balances screens, each with a Back button. Works on vanilla clients (no
   client mod).

The codebase is small (~2.7k LoC, 26 Java files), so both ship in one batch.

---

## Part A — Whitelist pricing

### Behaviour change

- An unpriced item returns **no price** (`null`) and is neither buyable nor
  sellable. There is no `defaultBuyPrice` / `defaultSellPrice` anymore.
- The `unbuyable` blacklist is removed entirely. "Not buyable" now means
  "has no buy price"; "not sellable" means "has no sell price".
- `/eco generateprices` keeps sourcing and categorizing every item, but seeds
  each new entry with `buy = null, sell = null` (a `-` skeleton) for the admin
  to fill in by hand.

### Code changes

- **`PriceBook`** — constructor takes only `Map<String, ItemPrice> prices`.
  Drop `defaultBuy`, `defaultSell`, `unbuyable`.
  - `getBuyPrice(id)` → explicit buy price or `null`.
  - `getSellPrice(id)` → explicit sell price or `null`.
  - `isBuyable(id)` → buy price present and non-null.
  - `isSellable(id)` → sell price present and non-null (new).
- **`WorthConfig`** — remove the `unbuyable` field and its handling in
  `normalize()`; `createDefault()` becomes an empty skeleton (no seeded
  blacklist). Update the class javadoc.
- **`EconomyConfig`** — remove `defaultBuyPrice` and `defaultSellPrice`.
- **`EconomyManager`** — build `new PriceBook(worthConfig.flatten())`; add
  `isSellable(id)`; `generatePrices(...)` inserts `new ItemPrice(null, null)`.
- **Commands (null-safety)** — `getSellPrice` / `getBuyPrice` can now be `null`.
  - `/buy` already guards `isBuyable`; safe.
  - `/sell` paths replace `price.compareTo(ZERO) <= 0` checks with
    `isSellable` so a `null` price can't NPE; message stays "This item cannot
    be sold."
  - `/worth` display paths show `"not sellable"` / `"not buyable"` instead of
    formatting a `null`. `/worth list` already prints `-` for nulls — unchanged.
- **`worth.json`** — delete the current untracked file; the admin regenerates it
  with `/eco generateprices`.

### Tests

`PriceBookTest` rewritten for whitelist semantics:
- Listed item returns its own buy/sell prices.
- Unlisted item returns `null` for both and is neither buyable nor sellable.
- Buy-only entry (sell `null`) is buyable but not sellable; sell-only is the
  reverse.
- Null/empty price map is treated as empty.

---

## Part B — Shop system (sgui)

### Dependency

Add `eu.pb4:sgui` (latest for 1.21.11) as `modImplementation` + `include`. Its
maven (`maven.nucleoid.xyz`) is already in `build.gradle`.

### Constraint

This is a server-side mod (`environment: "*"`, vanilla clients). We cannot open
the real creative inventory screen; the Buy screen *emulates* the tabbed
creative look using a chest container (a tab row + an item grid).

### Shared logic refactor

Extract a **`TradeService`** so the GUI and commands share one transaction +
logging path (no duplicated buy/sell/deposit/withdraw/pay logic):

- `buy(player, itemId, amount)` — debit, deliver via offer-or-drop, log
  `COMMAND_BUY`. Returns a result (OK / NOT_BUYABLE / INSUFFICIENT_FUNDS).
- `sell(player, itemId, amount)` — scan inventory for the item, sell up to
  `amount`, credit, log `COMMAND_SELL`. Returns a result (OK / NOT_SELLABLE /
  NONE_HELD / amount sold).
- Deposit, withdraw, and pay reuse the existing flows in `DepositCommand`,
  `BalanceCommands`, and `EconomyManager`; where the GUI needs them, factor the
  core (credit-first-then-consume for deposit; debit-then-deliver for withdraw;
  debit-credit for pay) into reusable methods rather than copying. Top Balances
  reuses `EconomyManager.getTopAccounts(...)`.

All money-moving paths keep the existing **safety ordering**: credit/debit the
balance first and only then consume or deliver items, so a storage failure never
destroys items or currency.

### Screens

**Hub GUI — `/shop`.** A chest menu whose slots are item-buttons:
- Buy → Buy GUI
- Sell → Sell GUI
- Deposit → Deposit GUI
- Withdraw → Withdraw GUI
- Transfer → Transfer GUI
- Top Balances → Top Balances GUI

A header item shows the player's current balance. New permission node
`savscommoneconomy.command.shop` (default allowed), via `PermissionsHelper`.

**Buy GUI.** Top row = category tab buttons (from `worth.json` category keys);
grid below = that category's `isBuyable` items, paged on overflow with
Prev/Next. Each item's tooltip shows its buy price. Click = buy 1, shift-click =
buy 64 (one stack). Currency item (emerald) never appears. Insufficient funds →
error feedback, GUI stays open, no change. Back button → hub.

**Sell GUI.** An empty drop-to-sell chest. The player drops stacks in; on
**close**, each stack is either:
- **sellable** (`isSellable`) → player credited `sellPrice × count`, item
  consumed; or
- **not sellable** → returned to the player's inventory (offer-or-drop).

**Item-safety is a hard correctness rule:** under *every* close path (Back
button, ESC, disconnect/logout, server stop) items must end up sold-and-credited
or back with the player — never voided. The close handler must fire on
disconnect. This is tested deliberately. Back button → hub.

**Deposit GUI.** Same drop-in pattern as Sell, restricted to emeralds. On close,
emeralds convert to balance minus the `depositFeePercent` fee (reusing the
deposit flow: credit net first, then consume emeralds); non-emerald items are
returned. Same item-safety rule. Back button → hub.

**Withdraw GUI.** Balance → emeralds, so there is nothing to drop in: an anvil
text-input for the amount → Confirm → emeralds delivered (debit first, then
offer-or-drop), mirroring the `/withdraw` rules (whole emeralds only, min 1, int
cap). Back button → hub.

**Transfer GUI.** Online players shown as clickable heads, paged. Select a
recipient → anvil text-input for the amount → Confirm → transfer via the pay
flow (cannot pay self; insufficient-funds guard). Recipient gets the existing
"Received X from Y" message. Back button → hub.

**Top Balances GUI.** Read-only leaderboard from `getTopAccounts(...)`: player
heads + names + formatted balances. Back button → hub.

### Commands

- `/buy <item> <quantity>` — unchanged (now naturally whitelist-aware).
- `/sell` (hand) and `/sell all` — **kept** as-is.
- `/sell <item> <quantity|all>` — **new**: scans the whole inventory for the
  item (like sell-all) and sells up to `quantity` (or everything with `all`).
  Errors cleanly if not sellable or none held.
- `/pay`, `/baltop`, `/deposit [all]`, `/withdraw` — unchanged. The GUIs are
  alternate access to the same logic.

### Testing

- `TradeService` buy/sell amount logic: unit-tested where it is Minecraft-free
  (price resolution, amount clamping, result codes). Inventory mutation and the
  sgui screens are verified manually in-game.
- **Item-safety** for Sell and Deposit GUIs is exercised explicitly in-game:
  close via Back, ESC, and disconnect; confirm no item loss and correct
  crediting.

---

## Out of scope

- Offline-player transfers (online only).
- Preset-amount buttons (amounts are typed via anvil).
- Shop search / favorites / per-player price overrides.
- Any change to storage schema or the Common Economy API integration.
