# Whitelist Pricing + Shop GUI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make item pricing a strict whitelist (no fallback, no blacklist) and add a server-side sgui shop hub (`/shop`) with Buy, Sell, Deposit, Withdraw, Transfer, and Top Balances screens.

**Architecture:** Part A reworks the pure `PriceBook` so an item is buyable/sellable only when it has an explicit buy/sell price. Part B extracts a shared `TradeService` + currency helpers so commands and GUI share one transaction path, then builds six `eu.pb4:sgui` screens reached from a hub. All money-moving paths credit/debit balance before consuming/delivering items so failures never destroy items.

**Tech Stack:** Java 21, Fabric (MC 1.21.11), Yarn mappings, `eu.pb4:sgui:1.12.0+1.21.11`, JUnit 5, Gson, existing SQLite storage.

**Spec:** `docs/superpowers/specs/2026-06-09-whitelist-pricing-and-shop-gui-design.md`

**Conventions for every task:**
- Build/test command (run from repo root): `./gradlew build`
- Run only the pricing tests: `./gradlew test --tests "savage.commoneconomy.economy.PriceBookTest"`
- Commit after each task with the message shown in its final step.
- Yarn mapping names used below: `ItemStack`, `Item`, `Items`, `Text`, `Identifier`, `ServerPlayerEntity`, `ScreenHandlerType`, `SlotActionType`, `net.minecraft.screen.slot.Slot`, `net.minecraft.inventory.SimpleInventory`.

---

## Phase A — Whitelist pricing

### Task 1: Rewrite `PriceBook` as a pure whitelist

**Files:**
- Modify: `src/main/java/savage/commoneconomy/economy/PriceBook.java`
- Test: `src/test/java/savage/commoneconomy/economy/PriceBookTest.java`

- [ ] **Step 1: Replace the test file with whitelist semantics**

Overwrite `src/test/java/savage/commoneconomy/economy/PriceBookTest.java`:

```java
package savage.commoneconomy.economy;

import org.junit.jupiter.api.Test;
import savage.commoneconomy.config.ItemPrice;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PriceBookTest {

    private PriceBook book() {
        Map<String, ItemPrice> prices = new HashMap<>();
        prices.put("minecraft:diamond", new ItemPrice(new BigDecimal("100"), new BigDecimal("60")));
        prices.put("minecraft:dirt", new ItemPrice(null, new BigDecimal("1")));      // sell-only
        prices.put("minecraft:bedrock", new ItemPrice(new BigDecimal("999"), null)); // buy-only
        return new PriceBook(prices);
    }

    @Test
    void listedItemUsesItsOwnPrices() {
        PriceBook b = book();
        assertEquals(0, b.getBuyPrice("minecraft:diamond").compareTo(new BigDecimal("100")));
        assertEquals(0, b.getSellPrice("minecraft:diamond").compareTo(new BigDecimal("60")));
        assertTrue(b.isBuyable("minecraft:diamond"));
        assertTrue(b.isSellable("minecraft:diamond"));
    }

    @Test
    void unlistedItemHasNoPriceAndIsNotTradeable() {
        PriceBook b = book();
        assertNull(b.getBuyPrice("minecraft:stone"));
        assertNull(b.getSellPrice("minecraft:stone"));
        assertFalse(b.isBuyable("minecraft:stone"));
        assertFalse(b.isSellable("minecraft:stone"));
    }

    @Test
    void sellOnlyItemIsSellableButNotBuyable() {
        PriceBook b = book();
        assertNull(b.getBuyPrice("minecraft:dirt"));
        assertFalse(b.isBuyable("minecraft:dirt"));
        assertEquals(0, b.getSellPrice("minecraft:dirt").compareTo(new BigDecimal("1")));
        assertTrue(b.isSellable("minecraft:dirt"));
    }

    @Test
    void buyOnlyItemIsBuyableButNotSellable() {
        PriceBook b = book();
        assertTrue(b.isBuyable("minecraft:bedrock"));
        assertNull(b.getSellPrice("minecraft:bedrock"));
        assertFalse(b.isSellable("minecraft:bedrock"));
    }

    @Test
    void nullMapIsTreatedAsEmpty() {
        PriceBook b = new PriceBook(null);
        assertNull(b.getBuyPrice("minecraft:dirt"));
        assertFalse(b.isBuyable("minecraft:dirt"));
        assertFalse(b.isSellable("minecraft:dirt"));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "savage.commoneconomy.economy.PriceBookTest"`
Expected: FAIL — `PriceBook(Map)` constructor and `isSellable` do not exist yet (compile error).

- [ ] **Step 3: Rewrite `PriceBook`**

Overwrite `src/main/java/savage/commoneconomy/economy/PriceBook.java`:

```java
package savage.commoneconomy.economy;

import savage.commoneconomy.config.ItemPrice;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Pure pricing logic. An item is tradeable only if worth.json gives it an
 * explicit price: a non-null buy price makes it buyable, a non-null sell price
 * makes it sellable. There is no fallback and no blacklist — unlisted items
 * have no price. Contains no Minecraft references so it can be unit tested.
 */
public class PriceBook {
    private final Map<String, ItemPrice> prices;

    public PriceBook(Map<String, ItemPrice> prices) {
        this.prices = prices != null ? new HashMap<>(prices) : new HashMap<>();
    }

    /** The buy price for an item, or null if it has none (not buyable). */
    public BigDecimal getBuyPrice(String itemId) {
        ItemPrice p = prices.get(itemId);
        return p != null ? p.buy : null;
    }

    /** The sell price for an item, or null if it has none (not sellable). */
    public BigDecimal getSellPrice(String itemId) {
        ItemPrice p = prices.get(itemId);
        return p != null ? p.sell : null;
    }

    public boolean isBuyable(String itemId) {
        return getBuyPrice(itemId) != null;
    }

    public boolean isSellable(String itemId) {
        return getSellPrice(itemId) != null;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "savage.commoneconomy.economy.PriceBookTest"`
Expected: PASS (5 tests). Note: the full project will not compile yet — `EconomyManager` still calls the old 4-arg constructor. That is fixed in Task 3.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/savage/commoneconomy/economy/PriceBook.java src/test/java/savage/commoneconomy/economy/PriceBookTest.java
git commit -m "feat: PriceBook is a strict whitelist (no fallback, no blacklist)"
```

---

### Task 2: Remove the `unbuyable` blacklist from `WorthConfig`

**Files:**
- Modify: `src/main/java/savage/commoneconomy/config/WorthConfig.java`

- [ ] **Step 1: Rewrite `WorthConfig` without `unbuyable`**

Overwrite `src/main/java/savage/commoneconomy/config/WorthConfig.java`:

```java
package savage.commoneconomy.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * worth.json: per-item buy/sell prices grouped by creative-tab category.
 * Items not listed have no price and cannot be bought or sold. An item with a
 * buy price can be bought; an item with a sell price can be sold; either may be
 * null ("-").
 *
 * Structure:
 *   categories: { "<tab>": { "<itemId>": { "buy": n, "sell": n } } }
 */
public class WorthConfig {
    public Map<String, Map<String, ItemPrice>> categories = new LinkedHashMap<>();

    /** Legacy flat field from older worth.json files; migrated into categories on load. */
    public Map<String, ItemPrice> itemPrices;

    /** Empty by default; Gson uses this for loading. Use createDefault() for a fresh seed. */
    public WorthConfig() {
    }

    /** A fresh, empty config. Items are added later by /eco generateprices. */
    public static WorthConfig createDefault() {
        return new WorthConfig();
    }

    /**
     * Ensure maps are non-null and migrate any legacy flat {@code itemPrices} into an
     * "uncategorized" category. Returns true if a legacy migration occurred.
     */
    public boolean normalize() {
        if (categories == null) categories = new LinkedHashMap<>();
        boolean migrated = false;
        if (itemPrices != null && !itemPrices.isEmpty()) {
            categories.computeIfAbsent("uncategorized", k -> new LinkedHashMap<>()).putAll(itemPrices);
            migrated = true;
        }
        itemPrices = null; // drop legacy so it is not re-serialized
        return migrated;
    }

    /** Flatten all category maps into a single id -> price lookup. */
    public Map<String, ItemPrice> flatten() {
        Map<String, ItemPrice> flat = new LinkedHashMap<>();
        if (categories != null) {
            for (Map<String, ItemPrice> cat : categories.values()) {
                if (cat != null) flat.putAll(cat);
            }
        }
        return flat;
    }

    /** True if the item id already has a price in any category. */
    public boolean contains(String itemId) {
        if (categories == null) return false;
        for (Map<String, ItemPrice> cat : categories.values()) {
            if (cat != null && cat.containsKey(itemId)) return true;
        }
        return false;
    }
}
```

- [ ] **Step 2: Commit**

(No standalone build here — `EconomyManager` references `worthConfig.unbuyable`, fixed next task.)

```bash
git add src/main/java/savage/commoneconomy/config/WorthConfig.java
git commit -m "feat: drop unbuyable blacklist from WorthConfig"
```

---

### Task 3: Remove default prices; update `EconomyManager`

**Files:**
- Modify: `src/main/java/savage/commoneconomy/config/EconomyConfig.java`
- Modify: `src/main/java/savage/commoneconomy/EconomyManager.java` (`priceBook()` ~324, `isBuyable`/add `isSellable` ~341, `generatePrices` ~423-444)

- [ ] **Step 1: Remove the default-price fields from `EconomyConfig`**

In `src/main/java/savage/commoneconomy/config/EconomyConfig.java`, delete these three lines:

```java
    // Fallback prices for items not listed in worth.json.
    public BigDecimal defaultBuyPrice = BigDecimal.valueOf(10);
    public BigDecimal defaultSellPrice = BigDecimal.valueOf(5);
```

- [ ] **Step 2: Fix the `priceBook()` factory in `EconomyManager`**

Replace the body of `priceBook()` (around line 319-331) with:

```java
    private PriceBook priceBook() {
        if (priceBook == null) {
            if (worthConfig == null) {
                loadWorthConfig();
            }
            priceBook = new PriceBook(worthConfig.flatten());
        }
        return priceBook;
    }
```

- [ ] **Step 3: Add `isSellable` next to `isBuyable`**

After the existing `isBuyable` method (around line 341-343) add:

```java
    public boolean isSellable(String itemId) {
        return priceBook().isSellable(itemId);
    }
```

- [ ] **Step 4: Make `generatePrices` write skeletons**

In `generatePrices(...)` (around line 436-438) replace the price insertion:

```java
            worthConfig.categories.computeIfAbsent(category, k -> new java.util.LinkedHashMap<>())
                    .put(id, new ItemPrice(null, null));
```

Also update the method's javadoc (around line 418-422) to read:

```java
    /**
     * Add a price-less entry (buy = null, sell = null) for every item id not already
     * listed, placing it in the given creative-tab category. Skips air and the currency
     * item. Saves worth.json (with backup) and rebuilds the price lookup. Returns the
     * number of new items added. Admins fill in real prices by editing worth.json.
     */
```

- [ ] **Step 5: Run the full build**

Run: `./gradlew build`
Expected: PASS — compiles and all tests green.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/savage/commoneconomy/config/EconomyConfig.java src/main/java/savage/commoneconomy/EconomyManager.java
git commit -m "feat: no fallback prices; generateprices writes skeletons; add isSellable"
```

---

### Task 4: Null-safe sell/worth commands

**Files:**
- Modify: `src/main/java/savage/commoneconomy/command/SellCommands.java`

`getSellPrice`/`getBuyPrice` can now return null. `BuyCommand` already guards `isBuyable` before reading the buy price, so it needs no change. `SellCommands` must guard with `isSellable` and never format a null price.

- [ ] **Step 1: Guard the three sell paths with `isSellable`**

In `SellCommands.java`, in `checkAllWorth` (~81), `sellHand` (~154), and `sellAll` (~192), replace each block of the form:

```java
        BigDecimal price = EconomyManager.getInstance().getSellPrice(itemId);

        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            context.getSource().sendError(Text.literal("This item cannot be sold."));
            return 0;
        }
```

with:

```java
        if (!EconomyManager.getInstance().isSellable(itemId)) {
            context.getSource().sendError(Text.literal("This item cannot be sold."));
            return 0;
        }
        BigDecimal price = EconomyManager.getInstance().getSellPrice(itemId);
```

- [ ] **Step 2: Null-safe display in `checkHandWorth`**

In `checkHandWorth` (~54-64) replace the price lookup + feedback with:

```java
        boolean sellable = EconomyManager.getInstance().isSellable(itemId);
        boolean buyable = EconomyManager.getInstance().isBuyable(itemId);
        BigDecimal sell = EconomyManager.getInstance().getSellPrice(itemId);
        BigDecimal buy = EconomyManager.getInstance().getBuyPrice(itemId);

        String sellText = sellable
                ? EconomyManager.getInstance().format(sell) + " each (total "
                    + EconomyManager.getInstance().format(sell.multiply(BigDecimal.valueOf(stack.getCount()))) + ")"
                : "not sellable";
        String buyText = buyable ? EconomyManager.getInstance().format(buy) + " each" : "not buyable";
        context.getSource().sendFeedback(() -> Text.literal(
                stack.getCount() + "x " + itemId + " | Sell: " + sellText + " | Buy: " + buyText), false);
        return 1;
```

- [ ] **Step 3: Null-safe display in `checkItemWorth`**

In `checkItemWorth` (~131-137) replace the price lookup + feedback with:

```java
        boolean sellable = EconomyManager.getInstance().isSellable(itemId);
        boolean buyable = EconomyManager.getInstance().isBuyable(itemId);
        BigDecimal sell = EconomyManager.getInstance().getSellPrice(itemId);
        BigDecimal buy = EconomyManager.getInstance().getBuyPrice(itemId);
        String sellText = sellable ? EconomyManager.getInstance().format(sell) + " each" : "not sellable";
        String buyText = buyable ? EconomyManager.getInstance().format(buy) + " each" : "not buyable";
        context.getSource().sendFeedback(() -> Text.literal(
                itemId + " | Sell: " + sellText + " | Buy: " + buyText), false);
        return 1;
```

- [ ] **Step 4: Build**

Run: `./gradlew build`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/savage/commoneconomy/command/SellCommands.java
git commit -m "fix: sell/worth commands handle items with no price"
```

---

### Task 5: Delete the stale `worth.json`

**Files:**
- Delete: `worth.json` (untracked working-tree file at repo root)

- [ ] **Step 1: Remove the file**

```bash
git rm --ignore-unmatch worth.json 2>/dev/null; rm -f worth.json
```

- [ ] **Step 2: Verify it is gone and Part A is green**

Run: `./gradlew build`
Expected: PASS. (A fresh empty `worth.json` is created on next server start; admin runs `/eco generateprices` to populate skeletons.)

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "chore: remove stale worth.json (regenerated as skeletons)"
```

---

## Phase B — Shared trade logic

### Task 6: Extract `TradeService`; route `/buy` and `/sell` through it

**Files:**
- Create: `src/main/java/savage/commoneconomy/economy/TradeService.java`
- Modify: `src/main/java/savage/commoneconomy/command/BuyCommand.java` (~40-85)
- Modify: `src/main/java/savage/commoneconomy/command/SellCommands.java` (`sellHand`/`sellAll`)

- [ ] **Step 1: Create `TradeService`**

Create `src/main/java/savage/commoneconomy/economy/TradeService.java`:

```java
package savage.commoneconomy.economy;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import savage.commoneconomy.EconomyManager;
import savage.commoneconomy.util.TransactionLogger;

import java.math.BigDecimal;

/**
 * One transaction path for buying and selling, shared by the /buy and /sell
 * commands and the shop GUI. Always moves the balance first, then items, so a
 * storage failure never destroys items or currency.
 */
public final class TradeService {
    private TradeService() {}

    public enum Status { OK, NOT_TRADEABLE, INSUFFICIENT_FUNDS, NONE_HELD, IS_CURRENCY, UNKNOWN_ITEM, FAILED }

    public record Result(Status status, int amount, BigDecimal total) {
        public boolean ok() { return status == Status.OK; }
        public static Result of(Status s) { return new Result(s, 0, BigDecimal.ZERO); }
    }

    /** Buy {@code amount} of an item, debiting the player and delivering the items. */
    public static Result buy(ServerPlayerEntity player, String itemId, int amount) {
        EconomyManager eco = EconomyManager.getInstance();
        Identifier id = Identifier.tryParse(itemId);
        if (id == null || !Registries.ITEM.containsId(id)) return Result.of(Status.UNKNOWN_ITEM);
        if (eco.isCurrencyItem(itemId)) return Result.of(Status.IS_CURRENCY);
        if (!eco.isBuyable(itemId)) return Result.of(Status.NOT_TRADEABLE);

        BigDecimal unit = eco.getBuyPrice(itemId);
        BigDecimal total = unit.multiply(BigDecimal.valueOf(amount));
        if (!eco.removeBalance(player.getUuid(), total)) return Result.of(Status.INSUFFICIENT_FUNDS);

        Item item = Registries.ITEM.get(id);
        int remaining = amount;
        int maxStack = new ItemStack(item).getMaxCount();
        while (remaining > 0) {
            int give = Math.min(remaining, maxStack);
            player.getInventory().offerOrDrop(new ItemStack(item, give));
            remaining -= give;
        }
        TransactionLogger.log("COMMAND_BUY", player.getName().getString(), "Server", total,
                "Bought " + amount + "x " + itemId);
        return new Result(Status.OK, amount, total);
    }

    /**
     * Sell up to {@code maxAmount} of an item found anywhere in the player's
     * inventory. Credits the player and removes the items.
     */
    public static Result sell(ServerPlayerEntity player, String itemId, int maxAmount) {
        EconomyManager eco = EconomyManager.getInstance();
        Identifier id = Identifier.tryParse(itemId);
        if (id == null || !Registries.ITEM.containsId(id)) return Result.of(Status.UNKNOWN_ITEM);
        if (eco.isCurrencyItem(itemId)) return Result.of(Status.IS_CURRENCY);
        if (!eco.isSellable(itemId)) return Result.of(Status.NOT_TRADEABLE);

        Item item = Registries.ITEM.get(id);
        int held = 0;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack s = player.getInventory().getStack(i);
            if (!s.isEmpty() && s.getItem() == item) held += s.getCount();
        }
        if (held <= 0) return Result.of(Status.NONE_HELD);

        int toSell = Math.min(maxAmount, held);
        BigDecimal unit = eco.getSellPrice(itemId);
        BigDecimal total = unit.multiply(BigDecimal.valueOf(toSell));
        if (!eco.addBalance(player.getUuid(), total)) return Result.of(Status.FAILED);

        int remaining = toSell;
        for (int i = 0; i < player.getInventory().size() && remaining > 0; i++) {
            ItemStack s = player.getInventory().getStack(i);
            if (!s.isEmpty() && s.getItem() == item) {
                int take = Math.min(remaining, s.getCount());
                s.decrement(take);
                remaining -= take;
            }
        }
        TransactionLogger.log("COMMAND_SELL", player.getName().getString(), "Server", total,
                "Sold " + toSell + "x " + itemId);
        return new Result(Status.OK, toSell, total);
    }
}
```

- [ ] **Step 2: Route `BuyCommand.buy` through `TradeService`**

Replace the `buy` method body in `BuyCommand.java` (everything after reading `itemId` and `amount`, lines ~46-84) with:

```java
        savage.commoneconomy.economy.TradeService.Result r =
                savage.commoneconomy.economy.TradeService.buy(player, itemId, amount);
        switch (r.status()) {
            case UNKNOWN_ITEM -> { context.getSource().sendError(Text.literal("Unknown item: " + itemId)); return 0; }
            case IS_CURRENCY -> { context.getSource().sendError(Text.literal("Emeralds are currency — use /withdraw instead.")); return 0; }
            case NOT_TRADEABLE -> { context.getSource().sendError(Text.literal("This item cannot be bought.")); return 0; }
            case INSUFFICIENT_FUNDS -> { context.getSource().sendError(Text.literal("Insufficient funds.")); return 0; }
            case OK -> {
                context.getSource().sendFeedback(() -> Text.literal("Bought " + r.amount() + "x " + itemId
                        + " for " + EconomyManager.getInstance().format(r.total())), false);
                return 1;
            }
            default -> { context.getSource().sendError(Text.literal("Purchase failed. Please try again.")); return 0; }
        }
```

Remove now-unused imports/locals in `BuyCommand` (`Item`, `ItemStack`, `BigDecimal`, `Registries` may still be used by the suggestion provider — keep what compiles; let `./gradlew build` guide removal).

- [ ] **Step 3: Route `sellHand` and `sellAll` through `TradeService`**

In `SellCommands.java` replace the transaction half of `sellHand` (after computing `itemId`, ~150-175) with:

```java
        savage.commoneconomy.economy.TradeService.Result r =
                savage.commoneconomy.economy.TradeService.sell(player, itemId, stack.getCount());
        return reportSell(context, r, itemId);
```

Replace the transaction half of `sellAll` (after computing `itemId`, ~188-229) with:

```java
        savage.commoneconomy.economy.TradeService.Result r =
                savage.commoneconomy.economy.TradeService.sell(player, itemId, Integer.MAX_VALUE);
        return reportSell(context, r, itemId);
```

Add this shared reporter method to `SellCommands`:

```java
    private static int reportSell(CommandContext<ServerCommandSource> context,
                                  savage.commoneconomy.economy.TradeService.Result r, String itemId) {
        switch (r.status()) {
            case IS_CURRENCY -> context.getSource().sendError(Text.literal("Emeralds are currency — use /deposit instead."));
            case NOT_TRADEABLE -> context.getSource().sendError(Text.literal("This item cannot be sold."));
            case NONE_HELD -> context.getSource().sendError(Text.literal("You have none of that item."));
            case OK -> { context.getSource().sendFeedback(() -> Text.literal("Sold " + r.amount() + "x " + itemId
                    + " for " + EconomyManager.getInstance().format(r.total())), false); return 1; }
            default -> context.getSource().sendError(Text.literal("Transaction failed. Please try again."));
        }
        return r.ok() ? 1 : 0;
    }
```

- [ ] **Step 4: Build**

Run: `./gradlew build`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: shared TradeService for /buy and /sell"
```

---

### Task 7: Add `/sell <item> <quantity|all>`

**Files:**
- Modify: `src/main/java/savage/commoneconomy/command/SellCommands.java` (`register`, ~32-36)

- [ ] **Step 1: Add the item/quantity subcommands**

In `register`, extend the `sell` literal so it keeps the existing `executes` (hand) and `all`, and adds an item argument with optional quantity / `all`:

```java
        dispatcher.register(CommandManager.literal("sell")
                .requires(source -> savage.commoneconomy.util.PermissionsHelper.check(source, "savscommoneconomy.command.sell", true))
                .executes(SellCommands::sellHand)
                .then(CommandManager.literal("all")
                        .executes(SellCommands::sellAll))
                .then(CommandManager.argument("item", IdentifierArgumentType.identifier())
                        .suggests(SELLABLE_SUGGESTIONS)
                        .executes(ctx -> sellItem(ctx, Integer.MAX_VALUE))
                        .then(CommandManager.literal("all")
                                .executes(ctx -> sellItem(ctx, Integer.MAX_VALUE)))
                        .then(CommandManager.argument("quantity", IntegerArgumentType.integer(1))
                                .executes(ctx -> sellItem(ctx, IntegerArgumentType.getInteger(ctx, "quantity"))))));
```

- [ ] **Step 2: Add the suggestion provider and `sellItem` handler**

Add near the top of `SellCommands` (after the class declaration):

```java
    private static final com.mojang.brigadier.suggestion.SuggestionProvider<ServerCommandSource> SELLABLE_SUGGESTIONS =
            (context, builder) -> net.minecraft.command.CommandSource.suggestMatching(
                    EconomyManager.getInstance().getAllItemPrices().keySet().stream()
                            .filter(id -> EconomyManager.getInstance().isSellable(id))
                            .toList(),
                    builder);

    private static int sellItem(CommandContext<ServerCommandSource> context, int maxAmount) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        String itemId = IdentifierArgumentType.getIdentifier(context, "item").toString();
        if (!net.minecraft.registry.Registries.ITEM.containsId(net.minecraft.util.Identifier.tryParse(itemId))) {
            context.getSource().sendError(Text.literal("Unknown item: " + itemId));
            return 0;
        }
        savage.commoneconomy.economy.TradeService.Result r =
                savage.commoneconomy.economy.TradeService.sell(player, itemId, maxAmount);
        return reportSell(context, r, itemId);
    }
```

Add the imports `com.mojang.brigadier.arguments.IntegerArgumentType` and (if missing) `com.mojang.brigadier.exceptions.CommandSyntaxException` — `./gradlew build` will flag any missing import.

- [ ] **Step 3: Build**

Run: `./gradlew build`
Expected: PASS. Manually confirm later in-game: `/sell minecraft:dirt 10`, `/sell minecraft:dirt all`, `/sell minecraft:dirt` (sells all).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/savage/commoneconomy/command/SellCommands.java
git commit -m "feat: /sell <item> <quantity|all>"
```

---

## Phase C — Shop hub (sgui)

### Task 8: Add the sgui dependency

**Files:**
- Modify: `build.gradle` (dependencies block, ~34-65)

- [ ] **Step 1: Add sgui to dependencies**

In `build.gradle`, inside the `dependencies { ... }` block (next to the other `eu.pb4` entry), add:

```groovy
	// Server-side GUI library
	modImplementation "eu.pb4:sgui:1.12.0+1.21.11"
	include "eu.pb4:sgui:1.12.0+1.21.11"
```

- [ ] **Step 2: Build to download and verify the dependency resolves**

Run: `./gradlew build`
Expected: PASS — sgui downloads from the already-configured `maven.nucleoid.xyz`.

- [ ] **Step 3: Commit**

```bash
git add build.gradle
git commit -m "build: add eu.pb4:sgui 1.12.0+1.21.11"
```

---

### Task 9: Shop hub GUI + `/shop` command

**Files:**
- Create: `src/main/java/savage/commoneconomy/gui/ShopHubGui.java`
- Create: `src/main/java/savage/commoneconomy/command/ShopCommand.java`
- Modify: `src/main/java/savage/commoneconomy/SavsCommonEconomy.java` (command list ~35-43)

This task wires a working hub whose buttons send a "coming soon" message; later tasks replace each callback with the real screen. This validates the sgui API and version against the real compiler before building six screens.

- [ ] **Step 1: Create the hub GUI**

Create `src/main/java/savage/commoneconomy/gui/ShopHubGui.java`:

```java
package savage.commoneconomy.gui;

import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import savage.commoneconomy.EconomyManager;

/** Root shop menu. Each slot is an item-button linking to a sub-screen. */
public class ShopHubGui extends SimpleGui {

    public ShopHubGui(ServerPlayerEntity player) {
        super(ScreenHandlerType.GENERIC_9X3, player, false);
        setTitle(Text.literal("Shop"));
        build();
    }

    private void build() {
        ServerPlayerEntity p = getPlayer();
        setSlot(4, new GuiElementBuilder(Items.EMERALD)
                .setName(Text.literal("Balance: " + EconomyManager.getInstance().format(
                        EconomyManager.getInstance().getBalance(p.getUuid())))));

        setButton(10, Items.DIAMOND, "Buy", () -> new BuyShopGui(p, null).open());
        setButton(11, Items.HOPPER, "Sell", () -> new SellShopGui(p).open());
        setButton(13, Items.EMERALD_BLOCK, "Deposit", () -> new DepositShopGui(p).open());
        setButton(14, Items.GOLD_INGOT, "Withdraw", () -> new WithdrawShopGui(p).open());
        setButton(15, Items.PAPER, "Transfer", () -> new TransferShopGui(p).open());
        setButton(16, Items.PLAYER_HEAD, "Top Balances", () -> new TopBalancesShopGui(p).open());
    }

    private void setButton(int slot, net.minecraft.item.Item icon, String name, Runnable onClick) {
        setSlot(slot, new GuiElementBuilder(icon)
                .setName(Text.literal(name))
                .setCallback((index, type, action, gui) -> onClick.run()));
    }
}
```

NOTE: `BuyShopGui`, `SellShopGui`, etc. do not exist yet. To validate the API in isolation first, temporarily replace each `() -> new XShopGui(p)...` with `() -> p.sendMessage(Text.literal("Coming soon"), false)`. Restore the real constructors as each screen task lands. The callback lambda arity `(index, type, action, gui)` matches sgui's `ClickCallback`; if `./gradlew build` reports a different arity for this sgui version, adjust to the signature the compiler reports (this is the one external-API risk and the build will catch it immediately).

- [ ] **Step 2: Create the `/shop` command**

Create `src/main/java/savage/commoneconomy/command/ShopCommand.java`:

```java
package savage.commoneconomy.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import savage.commoneconomy.gui.ShopHubGui;

public class ShopCommand {
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("shop")
                .requires(source -> savage.commoneconomy.util.PermissionsHelper.check(source, "savscommoneconomy.command.shop", true))
                .executes(ShopCommand::open));
    }

    private static int open(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        new ShopHubGui(player).open();
        return 1;
    }
}
```

- [ ] **Step 3: Register the command**

In `SavsCommonEconomy.java` add the import `import savage.commoneconomy.command.ShopCommand;` and add `ShopCommand::register,` to the `List.of(...)` of commands.

- [ ] **Step 4: Build**

Run: `./gradlew build`
Expected: PASS (with the temporary "Coming soon" callbacks).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: /shop hub GUI (buttons stubbed)"
```

---

## Phase D — Shop screens

> Each screen task: create the GUI class, wire its hub button (replace the stub from Task 9), `./gradlew build`, commit. Add a `Back` button (e.g. `Items.BARRIER` named "Back") that calls `new ShopHubGui(getPlayer()).open()`.

### Task 10: Buy screen (category tabs + item grid)

**Files:**
- Create: `src/main/java/savage/commoneconomy/gui/BuyShopGui.java`
- Modify: `src/main/java/savage/commoneconomy/gui/ShopHubGui.java` (restore the Buy button)

- [ ] **Step 1: Create the Buy GUI**

Create `src/main/java/savage/commoneconomy/gui/BuyShopGui.java`:

```java
package savage.commoneconomy.gui;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import savage.commoneconomy.EconomyManager;
import savage.commoneconomy.config.ItemPrice;
import savage.commoneconomy.economy.TradeService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Creative-style buy screen: top row = category tabs, grid below = buyable items, paged. */
public class BuyShopGui extends SimpleGui {
    private static final int TABS = 9;       // row 0
    private static final int GRID_START = 9;  // rows 1-4
    private static final int GRID_SIZE = 36;  // 4 rows * 9
    private static final int NAV_ROW = 45;    // row 5

    private final List<String> categories;
    private String category;
    private int page;

    public BuyShopGui(ServerPlayerEntity player, String category) {
        super(ScreenHandlerType.GENERIC_9X6, player, false);
        this.categories = new ArrayList<>(EconomyManager.getInstance().getBuyableCategories().keySet());
        this.category = category != null ? category : (categories.isEmpty() ? null : categories.get(0));
        this.page = 0;
        render();
    }

    private void render() {
        setTitle(Text.literal("Buy" + (category != null ? " — " + category : "")));
        // Tabs (first 9 categories; sufficient for vanilla — extend with tab paging if needed)
        for (int i = 0; i < TABS; i++) {
            if (i < categories.size()) {
                String cat = categories.get(i);
                boolean active = cat.equals(category);
                setSlot(i, new GuiElementBuilder(active ? Items.WRITABLE_BOOK : Items.BOOK)
                        .setName(Text.literal((active ? "▶ " : "") + cat))
                        .setCallback((idx, t, a, g) -> { this.category = cat; this.page = 0; render(); }));
            } else {
                setSlot(i, new GuiElementBuilder(Items.GRAY_STAINED_GLASS_PANE).setName(Text.literal(" ")));
            }
        }

        // Item grid for the active category
        Map<String, ItemPrice> items = category != null
                ? EconomyManager.getInstance().getBuyableCategories().getOrDefault(category, Map.of())
                : Map.of();
        List<Map.Entry<String, ItemPrice>> list = new ArrayList<>(items.entrySet());
        int from = page * GRID_SIZE;
        for (int slot = 0; slot < GRID_SIZE; slot++) {
            int idx = from + slot;
            if (idx < list.size()) {
                Map.Entry<String, ItemPrice> e = list.get(idx);
                setSlot(GRID_START + slot, buyButton(e.getKey(), e.getValue()));
            } else {
                setSlot(GRID_START + slot, new GuiElementBuilder(Items.AIR));
            }
        }

        // Nav row
        setSlot(NAV_ROW, new GuiElementBuilder(Items.BARRIER).setName(Text.literal("Back"))
                .setCallback((i, t, a, g) -> new ShopHubGui(getPlayer()).open()));
        setSlot(NAV_ROW + 3, new GuiElementBuilder(Items.ARROW).setName(Text.literal("Prev Page"))
                .setCallback((i, t, a, g) -> { if (page > 0) { page--; render(); } }));
        setSlot(NAV_ROW + 5, new GuiElementBuilder(Items.ARROW).setName(Text.literal("Next Page"))
                .setCallback((i, t, a, g) -> { if ((page + 1) * GRID_SIZE < list.size()) { page++; render(); } }));
    }

    private GuiElementBuilder buyButton(String itemId, ItemPrice price) {
        Item item = Registries.ITEM.get(Identifier.tryParse(itemId));
        return new GuiElementBuilder(item)
                .setName(Text.literal(itemId))
                .addLoreLine(Text.literal("Buy: " + EconomyManager.getInstance().format(price.buy) + " each"))
                .addLoreLine(Text.literal("Click: 1   Shift-click: 64"))
                .setCallback((idx, type, action, gui) -> {
                    int amount = type.shift ? 64 : 1;
                    TradeService.Result r = TradeService.buy(getPlayer(), itemId, amount);
                    feedback(r, itemId, true);
                    refreshBalanceTitle();
                });
    }

    private void feedback(TradeService.Result r, String itemId, boolean buying) {
        ServerPlayerEntity p = getPlayer();
        switch (r.status()) {
            case OK -> p.sendMessage(Text.literal((buying ? "Bought " : "Sold ") + r.amount() + "x " + itemId
                    + " for " + EconomyManager.getInstance().format(r.total())), true);
            case INSUFFICIENT_FUNDS -> p.sendMessage(Text.literal("Insufficient funds."), true);
            case NOT_TRADEABLE -> p.sendMessage(Text.literal("Not available."), true);
            default -> p.sendMessage(Text.literal("Transaction failed."), true);
        }
    }

    private void refreshBalanceTitle() {
        setTitle(Text.literal("Buy — " + category + "  |  "
                + EconomyManager.getInstance().format(EconomyManager.getInstance().getBalance(getPlayer().getUuid()))));
    }
}
```

NOTE on `type.shift`: sgui's `ClickType` exposes a `shift` boolean. If the build reports otherwise, use the `action`/`type` value the compiler exposes for shift-clicks.

- [ ] **Step 2: Add `getBuyableCategories()` to `EconomyManager`**

Add to `EconomyManager` (near `getAllItemPrices`, ~352-358):

```java
    /** Categories -> buyable items (buy price set), preserving worth.json order. Excludes currency. */
    public Map<String, Map<String, ItemPrice>> getBuyableCategories() {
        return filterCategories(true);
    }

    /** Categories -> sellable items (sell price set). Excludes currency. */
    public Map<String, Map<String, ItemPrice>> getSellableCategories() {
        return filterCategories(false);
    }

    private Map<String, Map<String, ItemPrice>> filterCategories(boolean buyable) {
        if (worthConfig == null) loadWorthConfig();
        Map<String, Map<String, ItemPrice>> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Map<String, ItemPrice>> cat : worthConfig.categories.entrySet()) {
            Map<String, ItemPrice> kept = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, ItemPrice> e : cat.getValue().entrySet()) {
                if (isCurrencyItem(e.getKey())) continue;
                BigDecimal price = buyable ? e.getValue().buy : e.getValue().sell;
                if (price != null) kept.put(e.getKey(), e.getValue());
            }
            if (!kept.isEmpty()) out.put(cat.getKey(), kept);
        }
        return out;
    }
```

- [ ] **Step 3: Restore the hub Buy button**

In `ShopHubGui`, set the Buy button callback back to `() -> new BuyShopGui(p, null).open()`.

- [ ] **Step 4: Build**

Run: `./gradlew build`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: shop Buy screen with category tabs"
```

---

### Task 11: Sell screen (drop-to-sell, item-safe on close)

**Files:**
- Create: `src/main/java/savage/commoneconomy/gui/SellShopGui.java`
- Modify: `src/main/java/savage/commoneconomy/gui/ShopHubGui.java` (restore the Sell button)

- [ ] **Step 1: Create the Sell GUI**

Create `src/main/java/savage/commoneconomy/gui/SellShopGui.java`:

```java
package savage.commoneconomy.gui;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import savage.commoneconomy.EconomyManager;
import savage.commoneconomy.economy.TradeService;

import java.math.BigDecimal;

/**
 * Drop-to-sell chest. The player places stacks into the open slots; on close,
 * each sellable stack is sold (credited) and the rest is returned. Items are
 * never destroyed: on every close path leftover stacks go back to the player.
 */
public class SellShopGui extends SimpleGui {
    private static final int DROP_SLOTS = 45; // rows 0-4
    private final SimpleInventory inv = new SimpleInventory(DROP_SLOTS);

    public SellShopGui(ServerPlayerEntity player) {
        super(ScreenHandlerType.GENERIC_9X6, player, false);
        setTitle(Text.literal("Sell — drop items, close to sell"));
        for (int i = 0; i < DROP_SLOTS; i++) {
            setSlotRedirect(i, new Slot(inv, i, 0, 0));
        }
        setSlot(49, new GuiElementBuilder(net.minecraft.item.Items.BARRIER).setName(Text.literal("Back / Sell & close"))
                .setCallback((i, t, a, g) -> close()));
    }

    @Override
    public void onClose() {
        ServerPlayerEntity p = getPlayer();
        BigDecimal totalCredited = BigDecimal.ZERO;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            String id = Registries.ITEM.getId(stack.getItem()).toString();
            if (EconomyManager.getInstance().isSellable(id)) {
                TradeService.Result r = sellStack(p, id, stack);
                if (r.ok()) {
                    totalCredited = totalCredited.add(r.total());
                    continue; // consumed
                }
            }
            // Not sellable or sale failed -> return to player, never void.
            p.getInventory().offerOrDrop(stack.copy());
        }
        inv.clear();
        if (totalCredited.signum() > 0) {
            p.sendMessage(Text.literal("Sold for " + EconomyManager.getInstance().format(totalCredited)), false);
        }
        super.onClose();
    }

    /** Credit the value of a single dropped stack directly (already removed from player inv). */
    private TradeService.Result sellStack(ServerPlayerEntity p, String id, ItemStack stack) {
        BigDecimal unit = EconomyManager.getInstance().getSellPrice(id);
        BigDecimal total = unit.multiply(BigDecimal.valueOf(stack.getCount()));
        if (!EconomyManager.getInstance().addBalance(p.getUuid(), total)) {
            return TradeService.Result.of(TradeService.Status.FAILED);
        }
        savage.commoneconomy.util.TransactionLogger.log("GUI_SELL", p.getName().getString(), "Server", total,
                "Sold " + stack.getCount() + "x " + id);
        return new TradeService.Result(TradeService.Status.OK, stack.getCount(), total);
    }
}
```

NOTE: `onClose()` is the sgui close hook (fires on Back, ESC, disconnect, and server stop). The `Slot` constructor `(Inventory, index, x, y)` uses dummy x/y because sgui positions slots itself.

- [ ] **Step 2: Restore the hub Sell button** to `() -> new SellShopGui(p).open()`.

- [ ] **Step 3: Build**

Run: `./gradlew build`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: shop Sell screen (drop-to-sell, item-safe close)"
```

---

### Task 12: Deposit screen (drop emeralds)

**Files:**
- Create: `src/main/java/savage/commoneconomy/gui/DepositShopGui.java`
- Modify: `src/main/java/savage/commoneconomy/EconomyManager.java` (add `depositEmeralds`)
- Modify: `src/main/java/savage/commoneconomy/command/DepositCommand.java` (use the shared method)
- Modify: `src/main/java/savage/commoneconomy/gui/ShopHubGui.java` (restore Deposit button)

- [ ] **Step 1: Add `depositEmeralds` to `EconomyManager`**

Add this method (it captures the existing deposit fee math so command + GUI share it):

```java
    public record DepositResult(int emeralds, BigDecimal net, BigDecimal fee, int feePercent, boolean ok) {}

    /** Convert {@code count} emeralds to balance minus the deposit fee. Credits first. */
    public DepositResult depositEmeralds(UUID uuid, int count) {
        int feePercent = Math.max(0, Math.min(100, config.depositFeePercent));
        BigDecimal gross = BigDecimal.valueOf(count);
        BigDecimal net = gross.multiply(BigDecimal.valueOf(100 - feePercent))
                .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.DOWN);
        BigDecimal fee = gross.subtract(net);
        boolean ok = addBalance(uuid, net);
        return new DepositResult(count, net, fee, feePercent, ok);
    }
```

- [ ] **Step 2: Use it from `DepositCommand`**

In `DepositCommand.deposit`, replace the fee math + `addBalance` block (~47-59) with:

```java
        EconomyManager.DepositResult dr = EconomyManager.getInstance().depositEmeralds(player.getUuid(), toDeposit);
        if (!dr.ok()) {
            context.getSource().sendError(Text.literal("Deposit failed, please try again. Your emeralds were not taken."));
            return 0;
        }
        final BigDecimal net = dr.net();
        final BigDecimal fee = dr.fee();
        final int feePercent = dr.feePercent();
```

(The emerald-consumption loop and feedback below stay as-is.)

- [ ] **Step 3: Create the Deposit GUI**

Create `src/main/java/savage/commoneconomy/gui/DepositShopGui.java`:

```java
package savage.commoneconomy.gui;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import savage.commoneconomy.EconomyManager;

/** Drop emeralds in; on close they convert to balance (minus fee). Non-emeralds are returned. */
public class DepositShopGui extends SimpleGui {
    private static final int DROP_SLOTS = 45;
    private final SimpleInventory inv = new SimpleInventory(DROP_SLOTS);

    public DepositShopGui(ServerPlayerEntity player) {
        super(ScreenHandlerType.GENERIC_9X6, player, false);
        setTitle(Text.literal("Deposit — drop emeralds, close to convert"));
        for (int i = 0; i < DROP_SLOTS; i++) {
            setSlotRedirect(i, new Slot(inv, i, 0, 0));
        }
        setSlot(49, new GuiElementBuilder(Items.BARRIER).setName(Text.literal("Back / Deposit & close"))
                .setCallback((i, t, a, g) -> close()));
    }

    @Override
    public void onClose() {
        ServerPlayerEntity p = getPlayer();
        int emeralds = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            if (stack.getItem() == Items.EMERALD) {
                emeralds += stack.getCount();
            } else {
                p.getInventory().offerOrDrop(stack.copy()); // return non-emeralds
            }
        }
        inv.clear();
        if (emeralds > 0) {
            EconomyManager.DepositResult dr = EconomyManager.getInstance().depositEmeralds(p.getUuid(), emeralds);
            if (dr.ok()) {
                p.sendMessage(Text.literal("Deposited " + emeralds + " emeralds → "
                        + EconomyManager.getInstance().format(dr.net()) + " (fee " + dr.feePercent() + "%)"), false);
            } else {
                // credit failed: return the emeralds so nothing is lost
                p.getInventory().offerOrDrop(new ItemStack(Items.EMERALD, emeralds));
                p.sendMessage(Text.literal("Deposit failed; emeralds returned."), false);
            }
        }
        super.onClose();
    }
}
```

- [ ] **Step 4: Restore the hub Deposit button** to `() -> new DepositShopGui(p).open()`.

- [ ] **Step 5: Build**

Run: `./gradlew build`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: shop Deposit screen + shared depositEmeralds"
```

---

### Task 13: Withdraw screen (anvil amount)

**Files:**
- Create: `src/main/java/savage/commoneconomy/gui/WithdrawShopGui.java`
- Modify: `src/main/java/savage/commoneconomy/EconomyManager.java` (add `withdrawEmeralds`)
- Modify: `src/main/java/savage/commoneconomy/command/BalanceCommands.java` (use the shared method)
- Modify: `src/main/java/savage/commoneconomy/gui/ShopHubGui.java` (restore Withdraw button)

- [ ] **Step 1: Add `withdrawEmeralds` to `EconomyManager`**

```java
    public enum WithdrawStatus { OK, TOO_SMALL, TOO_LARGE, INSUFFICIENT_FUNDS }
    public record WithdrawResult(WithdrawStatus status, int emeralds) {}

    /** Debit balance and deliver whole emeralds. Debits first, then delivers. */
    public WithdrawResult withdrawEmeralds(ServerPlayerEntity player, BigDecimal amount) {
        BigDecimal whole = amount.setScale(0, java.math.RoundingMode.DOWN);
        if (whole.compareTo(BigDecimal.ONE) < 0) return new WithdrawResult(WithdrawStatus.TOO_SMALL, 0);
        if (whole.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) return new WithdrawResult(WithdrawStatus.TOO_LARGE, 0);
        int emeralds = whole.intValueExact();
        BigDecimal cost = BigDecimal.valueOf(emeralds);
        if (!removeBalance(player.getUuid(), cost)) return new WithdrawResult(WithdrawStatus.INSUFFICIENT_FUNDS, 0);
        int remaining = emeralds;
        int maxStack = new net.minecraft.item.ItemStack(net.minecraft.item.Items.EMERALD).getMaxCount();
        while (remaining > 0) {
            int give = Math.min(remaining, maxStack);
            player.getInventory().offerOrDrop(new net.minecraft.item.ItemStack(net.minecraft.item.Items.EMERALD, give));
            remaining -= give;
        }
        savage.commoneconomy.util.TransactionLogger.log("WITHDRAW", player.getName().getString(), "Emeralds", cost, "Withdrawal");
        return new WithdrawResult(WithdrawStatus.OK, emeralds);
    }
```

Add `import net.minecraft.server.network.ServerPlayerEntity;` to `EconomyManager` if not present.

- [ ] **Step 2: Use it from `BalanceCommands.withdraw`**

Replace the body of `withdraw` after computing `amount` (~121-148) with:

```java
        EconomyManager.WithdrawResult wr = EconomyManager.getInstance().withdrawEmeralds(player, amount);
        switch (wr.status()) {
            case TOO_SMALL -> { context.getSource().sendError(Text.literal("Withdraw at least 1.")); return 0; }
            case TOO_LARGE -> { context.getSource().sendError(Text.literal("That's too many emeralds to withdraw at once (max " + Integer.MAX_VALUE + ").")); return 0; }
            case INSUFFICIENT_FUNDS -> { context.getSource().sendError(Text.literal("Insufficient funds.")); return 0; }
            case OK -> {
                CommandSupport.sendCommandFeedback(context, "Withdrew " + EconomyManager.getInstance().format(BigDecimal.valueOf(wr.emeralds())) + " as " + wr.emeralds() + " emeralds.", false);
                return 1;
            }
        }
        return 0;
```

- [ ] **Step 3: Create the Withdraw GUI**

Create `src/main/java/savage/commoneconomy/gui/WithdrawShopGui.java`:

```java
package savage.commoneconomy.gui;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.AnvilInputGui;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import savage.commoneconomy.EconomyManager;

import java.math.BigDecimal;

/** Type how many emeralds to withdraw, then confirm. */
public class WithdrawShopGui extends AnvilInputGui {

    public WithdrawShopGui(ServerPlayerEntity player) {
        super(player, false);
        setTitle(Text.literal("Withdraw — type an amount"));
        setDefaultInputValue("");
        setSlot(0, new GuiElementBuilder(Items.GOLD_INGOT).setName(Text.literal("Amount of emeralds")));
        setSlot(2, new GuiElementBuilder(Items.LIME_CONCRETE).setName(Text.literal("Confirm"))
                .setCallback((i, t, a, g) -> confirm()));
    }

    private void confirm() {
        ServerPlayerEntity p = getPlayer();
        BigDecimal amount;
        try {
            amount = new BigDecimal(getInput().trim());
        } catch (NumberFormatException e) {
            p.sendMessage(Text.literal("Enter a number."), false);
            return;
        }
        EconomyManager.WithdrawResult wr = EconomyManager.getInstance().withdrawEmeralds(p, amount);
        switch (wr.status()) {
            case OK -> p.sendMessage(Text.literal("Withdrew " + wr.emeralds() + " emeralds."), false);
            case TOO_SMALL -> p.sendMessage(Text.literal("Withdraw at least 1."), false);
            case TOO_LARGE -> p.sendMessage(Text.literal("Too many at once."), false);
            case INSUFFICIENT_FUNDS -> p.sendMessage(Text.literal("Insufficient funds."), false);
        }
        new ShopHubGui(p).open();
    }
}
```

NOTE: `setDefaultInputValue` is the sgui method to seed the anvil text field; if the compiler reports a different name (e.g. `setInputValue`), use that. The `getInput()` reads the typed text.

- [ ] **Step 4: Restore the hub Withdraw button** to `() -> new WithdrawShopGui(p).open()`.

- [ ] **Step 5: Build**

Run: `./gradlew build`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: shop Withdraw screen + shared withdrawEmeralds"
```

---

### Task 14: Transfer screen (online heads → anvil amount)

**Files:**
- Create: `src/main/java/savage/commoneconomy/gui/TransferShopGui.java`
- Create: `src/main/java/savage/commoneconomy/gui/TransferAmountGui.java`
- Modify: `src/main/java/savage/commoneconomy/EconomyManager.java` (add `transfer`)
- Modify: `src/main/java/savage/commoneconomy/command/BalanceCommands.java` (`pay` uses the shared method)
- Modify: `src/main/java/savage/commoneconomy/gui/ShopHubGui.java` (restore Transfer button)

- [ ] **Step 1: Add `transfer` to `EconomyManager`**

```java
    public enum TransferStatus { OK, SELF, INSUFFICIENT_FUNDS }

    /** Move {@code amount} from one account to another. Debits source, then credits target. */
    public TransferStatus transfer(UUID from, UUID to, BigDecimal amount) {
        if (from.equals(to)) return TransferStatus.SELF;
        if (!removeBalance(from, amount)) return TransferStatus.INSUFFICIENT_FUNDS;
        addBalance(to, amount);
        return TransferStatus.OK;
    }
```

- [ ] **Step 2: Use it from `BalanceCommands.pay`**

Replace the `removeBalance`/`addBalance` block in `pay` (~99-113) with:

```java
        EconomyManager.TransferStatus ts = EconomyManager.getInstance().transfer(sourcePlayer.getUuid(), targetUUID, amount);
        if (ts == EconomyManager.TransferStatus.INSUFFICIENT_FUNDS) {
            context.getSource().sendError(Text.literal("Insufficient funds."));
            return 0;
        }
        if (ts == EconomyManager.TransferStatus.SELF) {
            context.getSource().sendError(Text.literal("You cannot pay yourself."));
            return 0;
        }
        String formattedAmount = EconomyManager.getInstance().format(amount);
        CommandSupport.sendCommandFeedback(context, "Paid " + formattedAmount + " to " + displayName, false);
        ServerPlayerEntity target = context.getSource().getServer().getPlayerManager().getPlayer(targetUUID);
        if (target != null) {
            target.sendMessage(Text.literal("Received " + formattedAmount + " from " + sourcePlayer.getName().getString()), false);
        }
        savage.commoneconomy.util.TransactionLogger.log("PAY", sourcePlayer.getName().getString(), displayName, amount, "Payment");
        return 1;
```

(The earlier self-pay guard at ~94-97 can be removed since `transfer` handles it, but leaving it is harmless.)

- [ ] **Step 3: Create the recipient-picker GUI**

Create `src/main/java/savage/commoneconomy/gui/TransferShopGui.java`:

```java
package savage.commoneconomy.gui;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import savage.commoneconomy.EconomyManager;

import java.util.List;

/** Pick an online player to pay, shown as heads. */
public class TransferShopGui extends SimpleGui {
    private int page = 0;

    public TransferShopGui(ServerPlayerEntity player) {
        super(ScreenHandlerType.GENERIC_9X6, player, false);
        render();
    }

    private void render() {
        setTitle(Text.literal("Transfer — pick a player"));
        List<ServerPlayerEntity> online = EconomyManager.getInstance().getServer()
                .getPlayerManager().getPlayerList();
        int per = 45;
        int from = page * per;
        for (int slot = 0; slot < per; slot++) {
            int idx = from + slot;
            if (idx < online.size() && !online.get(idx).getUuid().equals(getPlayer().getUuid())) {
                ServerPlayerEntity target = online.get(idx);
                setSlot(slot, new GuiElementBuilder(Items.PLAYER_HEAD)
                        .setProfile(target.getGameProfile())
                        .setName(Text.literal(target.getName().getString()))
                        .setCallback((i, t, a, g) -> new TransferAmountGui(getPlayer(), target).open()));
            } else {
                setSlot(slot, new GuiElementBuilder(Items.AIR));
            }
        }
        setSlot(49, new GuiElementBuilder(Items.BARRIER).setName(Text.literal("Back"))
                .setCallback((i, t, a, g) -> new ShopHubGui(getPlayer()).open()));
        setSlot(48, new GuiElementBuilder(Items.ARROW).setName(Text.literal("Prev"))
                .setCallback((i, t, a, g) -> { if (page > 0) { page--; render(); } }));
        setSlot(50, new GuiElementBuilder(Items.ARROW).setName(Text.literal("Next"))
                .setCallback((i, t, a, g) -> { if ((page + 1) * 45 < online.size()) { page++; render(); } }));
    }
}
```

- [ ] **Step 4: Create the amount entry GUI**

Create `src/main/java/savage/commoneconomy/gui/TransferAmountGui.java`:

```java
package savage.commoneconomy.gui;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.AnvilInputGui;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import savage.commoneconomy.EconomyManager;

import java.math.BigDecimal;

/** Type the amount to send to the chosen recipient, then confirm. */
public class TransferAmountGui extends AnvilInputGui {
    private final ServerPlayerEntity target;

    public TransferAmountGui(ServerPlayerEntity sender, ServerPlayerEntity target) {
        super(sender, false);
        this.target = target;
        setTitle(Text.literal("Pay " + target.getName().getString()));
        setDefaultInputValue("");
        setSlot(0, new GuiElementBuilder(Items.PAPER).setName(Text.literal("Amount")));
        setSlot(2, new GuiElementBuilder(Items.LIME_CONCRETE).setName(Text.literal("Confirm"))
                .setCallback((i, t, a, g) -> confirm()));
    }

    private void confirm() {
        ServerPlayerEntity sender = getPlayer();
        BigDecimal amount;
        try {
            amount = new BigDecimal(getInput().trim());
        } catch (NumberFormatException e) {
            sender.sendMessage(Text.literal("Enter a number."), false);
            return;
        }
        if (amount.signum() <= 0) {
            sender.sendMessage(Text.literal("Enter a positive amount."), false);
            return;
        }
        EconomyManager eco = EconomyManager.getInstance();
        EconomyManager.TransferStatus ts = eco.transfer(sender.getUuid(), target.getUuid(), amount);
        switch (ts) {
            case OK -> {
                sender.sendMessage(Text.literal("Paid " + eco.format(amount) + " to " + target.getName().getString()), false);
                target.sendMessage(Text.literal("Received " + eco.format(amount) + " from " + sender.getName().getString()), false);
                savage.commoneconomy.util.TransactionLogger.log("PAY", sender.getName().getString(),
                        target.getName().getString(), amount, "GUI payment");
            }
            case SELF -> sender.sendMessage(Text.literal("You cannot pay yourself."), false);
            case INSUFFICIENT_FUNDS -> sender.sendMessage(Text.literal("Insufficient funds."), false);
        }
        new ShopHubGui(sender).open();
    }
}
```

- [ ] **Step 5: Restore the hub Transfer button** to `() -> new TransferShopGui(p).open()`.

- [ ] **Step 6: Build**

Run: `./gradlew build`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: shop Transfer screen + shared transfer()"
```

---

### Task 15: Top Balances screen

**Files:**
- Create: `src/main/java/savage/commoneconomy/gui/TopBalancesShopGui.java`
- Modify: `src/main/java/savage/commoneconomy/gui/ShopHubGui.java` (restore Top Balances button)

- [ ] **Step 1: Create the GUI**

Create `src/main/java/savage/commoneconomy/gui/TopBalancesShopGui.java`:

```java
package savage.commoneconomy.gui;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import savage.commoneconomy.EconomyManager;

import java.util.List;

/** Read-only leaderboard of the top balances. */
public class TopBalancesShopGui extends SimpleGui {

    public TopBalancesShopGui(ServerPlayerEntity player) {
        super(ScreenHandlerType.GENERIC_9X6, player, false);
        setTitle(Text.literal("Top Balances"));
        List<EconomyManager.AccountData> top = EconomyManager.getInstance().getTopAccounts(45);
        for (int i = 0; i < 45; i++) {
            if (i < top.size()) {
                EconomyManager.AccountData a = top.get(i);
                setSlot(i, new GuiElementBuilder(Items.PLAYER_HEAD)
                        .setProfile(a.name)
                        .setName(Text.literal("#" + (i + 1) + " " + a.name))
                        .addLoreLine(Text.literal(EconomyManager.getInstance().format(a.balance))));
            } else {
                setSlot(i, new GuiElementBuilder(Items.AIR));
            }
        }
        setSlot(49, new GuiElementBuilder(Items.BARRIER).setName(Text.literal("Back"))
                .setCallback((i, t, a, g) -> new ShopHubGui(getPlayer()).open()));
    }
}
```

NOTE: `setProfile(String name)` resolves a head by username (it may render the default head until the profile resolves; acceptable for a leaderboard). If unavailable, fall back to `Items.PLAYER_HEAD` without a profile.

- [ ] **Step 2: Restore the hub Top Balances button** to `() -> new TopBalancesShopGui(p).open()`.

- [ ] **Step 3: Build**

Run: `./gradlew build`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: shop Top Balances screen"
```

---

### Task 16: Integration + manual verification

**Files:** none (verification only)

- [ ] **Step 1: Full build**

Run: `./gradlew build`
Expected: PASS, all tests green.

- [ ] **Step 2: Launch a dev server and run the manual checklist**

Run: `./gradlew runServer` (op yourself: `op <name>` in server console), then in-game:

- `/eco generateprices` → open `worth.json`, confirm entries have `"buy": null, "sell": null` and there is **no** `unbuyable` array.
- Hand-edit a few prices (e.g. give `minecraft:dirt` a sell, `minecraft:diamond` a buy+sell), `/eco reload`.
- `/worth minecraft:stone` (unpriced) → "not sellable | not buyable". `/buy minecraft:stone 1` → "cannot be bought". `/sell minecraft:stone` → "cannot be sold".
- `/buy minecraft:diamond 3`, `/sell minecraft:dirt 5`, `/sell minecraft:dirt all`.
- `/shop` → hub opens; each button opens its screen; every screen's Back returns to the hub.
- **Buy screen:** tabs switch category; click buys 1, shift-click buys 64; insufficient funds shows a message and does not deduct.
- **Sell screen item-safety:** drop sellable + unsellable + a non-priced item; close via Back, via ESC, and by disconnecting mid-open. Each time: sellable credited, everything else back in inventory, nothing lost.
- **Deposit screen:** drop emeralds + a non-emerald; close → emeralds converted (fee applied), non-emerald returned.
- **Withdraw screen:** type an amount → emeralds delivered, balance debited; typing `0`/garbage is rejected.
- **Transfer screen:** pick an online player, type amount, confirm → both players messaged, balances move; self not shown.
- **Top Balances:** shows ranked heads + balances, read-only.

- [ ] **Step 3: Commit any fixes found during verification**

```bash
git add -A
git commit -m "fix: shop GUI verification fixes"
```

---

## Self-Review notes (for the implementer)

- **Spec coverage:** Part A (Tasks 1-5), TradeService + `/sell` form (Tasks 6-7), sgui dep (8), hub (9), Buy/Sell/Deposit/Withdraw/Transfer/Top (10-15), item-safety verification (11/12/16). All spec sections map to a task.
- **The one external-API risk is sgui method/lambda signatures** (`ClickCallback` arity, `ClickType.shift`, `AnvilInputGui.setDefaultInputValue`, `GuiElementBuilder.setProfile`). Task 9 surfaces these against the real compiler before the six screens are built; if any differ for `1.12.0+1.21.11`, adjust to what `./gradlew build` reports — the shapes above match recent sgui.
- **Item-safety invariant** (Sell/Deposit `onClose`): every dropped stack is either credited or returned via `offerOrDrop`; `inv.clear()` only runs after that loop. Never void.
