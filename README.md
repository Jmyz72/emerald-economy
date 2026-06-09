# Emerald Economy

My server-side economy mod for my Minecraft **1.21.11** (Fabric) server. Vanilla emeralds are the physical currency: players convert emeralds to a stored balance and back, buy and sell items against a price list I curate, and trade through a chest-style shop GUI. It's server-side only — no client mod needed, everything renders on vanilla clients.

This README is my own reference for how the mod works and how to run it.

## How the economy works

- **Emeralds are the currency.** `$1 = 1 emerald`. Deposit emeralds to bank them, withdraw to get them back.
- **Whitelist pricing.** An item is tradeable only if I give it an explicit price in `worth.json`. A `buy` price makes it buyable, a `sell` price makes it sellable; anything unpriced can't be traded. No global fallback price, no blacklist.
- **Balance** is the banked money, shown with the currency symbol (default `$`). Emeralds themselves are never priced as a tradeable good — they *are* the currency.
- `/deposit` converts inventory emeralds to balance minus a configurable fee (`depositFeePercent`, default 20% burned → keep 80%). `/withdraw` converts balance back into whole emeralds. `/buy` spends balance for items, `/sell` removes items for balance, `/pay` transfers between players.
- Storage is SQLite (via savdbcore) with caching and optimistic locking for safe concurrent updates.

## Requirements

- Minecraft 1.21.11, Fabric Loader ≥ 0.19.3, Fabric API, Java 21
- Bundled inside the jar (no separate downloads): sgui, fabric-permissions-api, savdbcore, common-economy-api

## Commands

### Player
| Command | Description |
|---|---|
| `/bal` · `/bal <player>` | Your balance, or another player's (online or offline). |
| `/baltop` | Top balances. |
| `/pay <player> <amount>` | Send balance to another player. |
| `/deposit <amount>` · `/deposit all` | Convert emeralds → balance (minus deposit fee). |
| `/withdraw <amount>` | Convert balance → emeralds. |
| `/buy <item> <amount>` | Buy a priced item. |
| `/sell` · `/sell all` | Sell the held stack / all matching it in your inventory. |
| `/sell <item> <amount\|all>` | Sell a specific item by id from your inventory. |
| `/worth` · `/worth all` · `/worth <item>` | Show an item's buy/sell price (or "not buyable/sellable"). |
| `/worth list` | List every priced item. |
| `/daily` | Claim the daily reward. |
| `/shop` | Open the shop GUI hub. |

### Shop GUI (`/shop`)
Each hub slot is an item-button:
- **Buy** — a creative-style screen with a category tab row (from the `worth.json` categories) and a paged item grid. Click to buy 1, shift-click to buy 64.
- **Sell** — drop item stacks in and close; sellable items are sold and credited, everything else is returned. No items are ever lost, even on disconnect.
- **Deposit** — drop emeralds in and close; they convert to balance (minus fee). Non-emeralds are returned.
- **Withdraw** — type an amount, confirm, receive emeralds.
- **Transfer** — pick an online player, type an amount, confirm.
- **Top Balances** — read-only leaderboard.

### Admin (permission `emeraldeconomy.admin`, or OP level 2)
| Command | Description |
|---|---|
| `/givemoney <player> <amount>` | Add balance. |
| `/takemoney <player> <amount>` | Remove balance. |
| `/setmoney <player> <amount>` | Set balance. |
| `/resetmoney <player>` | Reset to the default starting balance. |
| `/eco generateprices` | Populate `worth.json` with a price-less (`-`) entry for every item, grouped by creative tab — a skeleton to fill in. |
| `/eco reload` | Reload `worth.json` after editing (no restart needed). |
| `/ecolog <target> <time> <unit> [page]` | Search transaction logs (`unit` = `s`/`m`/`h`/`d`; `target` = name or `*`). |
| `/ecodebug verify` · `cleanup` · `api` | Storage/transaction diagnostics. |

## Pricing (`worth.json`)

Located at `config/emerald-economy/worth.json`. Prices are grouped by creative-tab category. Each item has a `buy` and/or `sell` price; either may be `null` (shown as `-`), and an unlisted item has no price at all.

```json
{
  "categories": {
    "building_blocks": {
      "minecraft:stone": { "buy": 5, "sell": 1 }
    },
    "ingredients": {
      "minecraft:diamond": { "buy": 100, "sell": 60 },
      "minecraft:emerald_ore": { "buy": null, "sell": 25 }
    }
  }
}
```

- `buy` set → the item can be **bought**; `sell` set → it can be **sold**.
- Both `null` → the item is listed but **not tradeable** (a skeleton awaiting prices).

**My workflow:** run `/eco generateprices` once to source and categorize every item as `-` skeletons, hand-edit the prices I want, then `/eco reload`. (The `tools/pricing/` Python scripts help derive prices from recipes.)

## Configuration (`config/emerald-economy/config.json`)

```json
{
  "defaultBalance": 1000,
  "currencySymbol": "$",
  "depositFeePercent": 20,
  "storage": {
    "tablePrefix": "emerald_eco_",
    "poolSize": 10,
    "connectionTimeout": 30000,
    "idleTimeout": 600000
  }
}
```

- `defaultBalance` — starting balance for new players.
- `currencySymbol` — currency display symbol.
- `depositFeePercent` — percent of deposited emerald value burned as a fee (0–100; 20 = keep 80%).
- `storage.*` — SQLite table prefix and connection-pool tuning. The database file (`economy_data.sqlite`) lives in the same config directory.

## Permissions

Uses the Fabric Permissions API; with LuckPerms (or any implementer) installed I can manage nodes. Without a permissions mod it falls back to vanilla OP levels (admin = level 2).

- **Player nodes** (default allowed): `emeraldeconomy.command.bal`, `…bal.others`, `…baltop`, `…pay`, `…deposit`, `…withdraw`, `…buy`, `…sell`, `…worth`, `…daily`, `…shop`
- **Admin node** (default OP level 2): `emeraldeconomy.admin` — `/givemoney`, `/takemoney`, `/setmoney`, `/resetmoney`, `/eco`, `/ecolog`, `/ecodebug`.

## Building

Requires JDK 21. From the repo root:

```bash
./gradlew build
```

The jar lands at `build/libs/emerald-economy-<version>.jar`.

## Notes

- Registers a Common Economy API provider (id `emerald_economy`, currency `emerald_economy:dollar`) so other economy-aware mods on the server can read and modify balances. The provider lives in `src/main/java/savage/emeraldeconomy/integration/`.
- License: CC0-1.0.
