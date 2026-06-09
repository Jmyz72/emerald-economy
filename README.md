# Emerald Economy

A **server-side** economy mod for Minecraft **1.21.11** (Fabric). Vanilla **emeralds are the physical currency**: players convert emeralds to a stored balance and back, buy and sell items against an admin-curated price list, and trade through a clean chest-style shop GUI. No client mod required — everything renders on vanilla clients.

## Highlights

- **Emeralds as currency** — `$1 = 1 emerald`. Deposit emeralds to bank them, withdraw to get them back.
- **Whitelist pricing** — items are tradeable only if you give them an explicit price in `worth.json`. A buy price makes an item buyable; a sell price makes it sellable; anything unpriced simply can't be traded. No global fallback price, no blacklist.
- **Server-side shop GUI** (built on [sgui](https://github.com/Patbox/sgui)) — `/shop` opens a hub with Buy, Sell, Deposit, Withdraw, Transfer, and Top Balances screens. Works on vanilla clients.
- **Commands** for everything the GUI does, for players who prefer typing.
- **SQLite storage** with Caffeine caching and optimistic locking for safe concurrent updates.
- **Common Economy API** provider (`emerald_economy`) — integrates with mods like [Universal Shops](https://modrinth.com/mod/universal-shops) and [Mob Money](https://modrinth.com/mod/mob-money).
- **Permissions** via the [Fabric Permissions API](https://github.com/lucko/fabric-permissions-api) (LuckPerms etc.), with a vanilla OP-level fallback.

## Requirements

- Minecraft 1.21.11, Fabric Loader ≥ 0.19.3
- Fabric API
- Java 21

Bundled libraries (sgui, fabric-permissions-api, savdbcore, common-economy-api) are shipped inside the jar — no separate downloads needed.

## Currency model

- **Balance** is the banked money, shown with the currency symbol (default `$`).
- **`/deposit`** converts emeralds in your inventory to balance, minus a configurable fee (`depositFeePercent`, default 20% burned → you keep 80%).
- **`/withdraw`** converts balance back into whole emeralds (1 emerald = $1).
- **`/buy`** spends balance and gives you the item; **`/sell`** removes items and credits balance.
- **`/pay`** transfers balance between players.

Emeralds themselves are never "priced" as a tradeable good — they are the currency.

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
| `/shop` | Open the shop GUI hub. |

### Shop GUI (`/shop`)
Each hub slot is an item-button:
- **Buy** — a creative-style screen with a category tab row (from your `worth.json` categories) and a paged item grid. Click to buy 1, shift-click to buy 64.
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
| `/eco generateprices` | Populate `worth.json` with a price-less (`-`) entry for every item, grouped by creative tab — a skeleton for you to fill in. |
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

**Typical workflow:** run `/eco generateprices` once to source and categorize every item as `-` skeletons, hand-edit the prices you want, then `/eco reload`.

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

Uses the Fabric Permissions API; install LuckPerms (or any implementer) to manage nodes. Without a permissions mod, the mod falls back to vanilla OP levels (admin = level 2).

**Player nodes (default allowed):**
`emeraldeconomy.command.bal`, `…bal.others`, `…baltop`, `…pay`, `…deposit`, `…withdraw`, `…buy`, `…sell`, `…worth`, `…shop`

**Admin node (default OP level 2):**
`emeraldeconomy.admin` — `/givemoney`, `/takemoney`, `/setmoney`, `/resetmoney`, `/eco`, `/ecolog`, `/ecodebug`.

## Building

Requires JDK 21. From the repo root:

```bash
./gradlew build
```

The mod jar is produced at `build/libs/emerald-economy-<version>.jar`.

## Mod integration (Common Economy API)

Emerald Economy registers a [Common Economy API](https://github.com/Patbox/common-economy-api) provider with id **`emerald_economy`** and currency **`emerald_economy:dollar`**, so other economy-aware mods can read and modify balances. See `src/main/java/savage/emeraldeconomy/integration/` for the provider implementation and integration notes.

## License

CC0-1.0.
