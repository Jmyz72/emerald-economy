# Server-Wide Command Economy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert the Savs Common Economy mod into a single-server command economy where every item has a buy/sell price and players trade from anywhere via commands, removing the physical chest-shop system and all non-SQLite storage.

**Architecture:** Pure pricing logic lives in a new testable `PriceBook` class (curated per-item buy/sell prices + global fallback + an unbuyable blacklist). `EconomyManager` owns a `PriceBook` built from `WorthConfig`. Commands (`/buy`, `/sell`, `/worth`) call the manager. The shop package, JSON/MySQL/Postgres storage, and the Redis sync layer are deleted. SQLite (via savdbcore) is the only backend.

**Tech Stack:** Java 21, Fabric Loom (MC 1.21.11), Gson config, Caffeine cache, savdbcore (SQLite), Common Economy API, JUnit 5 (new, for pure-logic tests).

---

## Testing Approach (read first)

Minecraft-coupled code (commands, event handlers, storage tied to savdbcore) cannot be unit-tested without a running game. This plan therefore uses two verification modes:

- **Unit tests (JUnit 5):** only for `PriceBook`, which is pure Java (Strings + BigDecimal, no Minecraft imports). These follow strict TDD (test first, watch it fail, implement, watch it pass).
- **Compile + smoke test:** every other task is verified with `./gradlew build` (must compile and remap cleanly) and a final in-game smoke checklist (Task 12).

Some intermediate tasks intentionally leave the project non-compiling because the removal of shops/Redis/storage is interdependent. Each such task says so, and the first green `./gradlew build` is expected at **Task 11**.

On Windows use `gradlew.bat` instead of `./gradlew`.

---

## File Structure

**Create:**
- `src/main/java/savage/commoneconomy/config/ItemPrice.java` — buy/sell price pair (config DTO)
- `src/main/java/savage/commoneconomy/economy/PriceBook.java` — pure pricing/blacklist logic
- `src/main/java/savage/commoneconomy/command/BuyCommand.java` — `/buy <item> <amount>`
- `src/main/java/savage/commoneconomy/command/DepositCommand.java` — `/deposit <amount>` / `/deposit all`
- `src/test/java/savage/commoneconomy/economy/PriceBookTest.java` — unit tests

**Currency note:** `minecraft:emerald` is the physical currency (1 emerald = $1).
`/withdraw` gives vanilla emeralds (no fee); `/deposit` converts emeralds back to
balance minus a 20% fee. Emerald is hardcoded-excluded from `/buy` and `/sell`,
and the old right-click bank-note redemption is removed.

**Modify:**
- `build.gradle` — add JUnit; remove Savs-Redis-Lib dependency
- `src/main/java/savage/commoneconomy/config/WorthConfig.java` — buy/sell map + unbuyable list + defaults
- `src/main/java/savage/commoneconomy/config/EconomyConfig.java` — strip toggles/redis/network-storage; add default buy/sell prices
- `src/main/java/savage/commoneconomy/EconomyManager.java` — SQLite-only, PriceBook wiring, remove Redis, chat-only format
- `src/main/java/savage/commoneconomy/SavsCommonEconomy.java` — remove shop handlers + Redis init; register `/buy`
- `src/main/java/savage/commoneconomy/command/EconomyCommands.java` — remove Redis calls + notification-mode branching; `/withdraw` gives emeralds
- `src/main/java/savage/commoneconomy/command/SellCommands.java` — always register; `/worth` shows buy+sell; sell uses sell price; reject emerald

**Delete:**
- entire `src/main/java/savage/commoneconomy/shop/` package (9 files)
- `src/main/java/savage/commoneconomy/storage/JsonStorage.java`
- `src/main/java/savage/commoneconomy/storage/MysqlStorage.java`
- `src/main/java/savage/commoneconomy/storage/PostgresStorage.java`
- `src/main/java/savage/commoneconomy/util/RedisManager.java`
- `src/main/java/savage/commoneconomy/util/RedisBackend.java`
- `src/main/java/savage/commoneconomy/util/RealRedisBackend.java`

---

## Task 1: Add JUnit 5 test infrastructure

**Files:**
- Modify: `build.gradle`

- [ ] **Step 1: Add the JUnit dependency**

In `build.gradle`, inside the FIRST `dependencies { ... }` block (the one starting at line 29), add at the end (before the closing `}`):

```gradle
	// Unit testing (pure-logic only)
	testImplementation "org.junit.jupiter:junit-jupiter:5.10.2"
	testRuntimeOnly "org.junit.platform:junit-platform-launcher"
```

- [ ] **Step 2: Enable the JUnit platform**

In `build.gradle`, after the `java { ... }` block (ends at line 92), add:

```gradle
test {
	useJUnitPlatform()
}
```

- [ ] **Step 3: Verify Gradle accepts the config**

Run: `./gradlew help`
Expected: `BUILD SUCCESSFUL` (no script evaluation errors).

- [ ] **Step 4: Commit**

```bash
git add build.gradle
git commit -m "build: add JUnit 5 test infrastructure"
```

---

## Task 2: Create the ItemPrice config DTO

**Files:**
- Create: `src/main/java/savage/commoneconomy/config/ItemPrice.java`

- [ ] **Step 1: Create the class**

Create `src/main/java/savage/commoneconomy/config/ItemPrice.java`:

```java
package savage.commoneconomy.config;

import java.math.BigDecimal;

/**
 * A buy/sell price pair for a single item, as stored in worth.json.
 * buy  = price a player pays to /buy one unit
 * sell = price a player receives to /sell one unit
 */
public class ItemPrice {
    public BigDecimal buy;
    public BigDecimal sell;

    public ItemPrice() {
    }

    public ItemPrice(BigDecimal buy, BigDecimal sell) {
        this.buy = buy;
        this.sell = sell;
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL` (this class has no dependents yet, so it compiles standalone even though later tasks still reference old code — if the rest of the project currently compiles, it stays compiling here).

- [ ] **Step 3: Commit**

```bash
git add src/main/java/savage/commoneconomy/config/ItemPrice.java
git commit -m "feat: add ItemPrice buy/sell DTO"
```

---

## Task 3: Build the PriceBook pure logic (TDD)

`PriceBook` is the only unit-tested class. It resolves buy/sell prices with a fallback and enforces the unbuyable blacklist. It uses only `String`, `BigDecimal`, `Map`, `Set` — no Minecraft imports — so it runs under plain JUnit.

**Files:**
- Test: `src/test/java/savage/commoneconomy/economy/PriceBookTest.java`
- Create: `src/main/java/savage/commoneconomy/economy/PriceBook.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/savage/commoneconomy/economy/PriceBookTest.java`:

```java
package savage.commoneconomy.economy;

import org.junit.jupiter.api.Test;
import savage.commoneconomy.config.ItemPrice;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PriceBookTest {

    private PriceBook book() {
        Map<String, ItemPrice> prices = new HashMap<>();
        prices.put("minecraft:diamond", new ItemPrice(new BigDecimal("100"), new BigDecimal("60")));
        List<String> unbuyable = List.of("minecraft:bedrock");
        return new PriceBook(prices, unbuyable, new BigDecimal("10"), new BigDecimal("5"));
    }

    @Test
    void listedItemUsesItsOwnPrices() {
        PriceBook b = book();
        assertEquals(0, b.getBuyPrice("minecraft:diamond").compareTo(new BigDecimal("100")));
        assertEquals(0, b.getSellPrice("minecraft:diamond").compareTo(new BigDecimal("60")));
    }

    @Test
    void unlistedItemFallsBackToDefaults() {
        PriceBook b = book();
        assertEquals(0, b.getBuyPrice("minecraft:dirt").compareTo(new BigDecimal("10")));
        assertEquals(0, b.getSellPrice("minecraft:dirt").compareTo(new BigDecimal("5")));
    }

    @Test
    void blacklistedItemIsNotBuyableButStillSellable() {
        PriceBook b = book();
        assertFalse(b.isBuyable("minecraft:bedrock"));
        assertTrue(b.isBuyable("minecraft:diamond"));
        assertTrue(b.isBuyable("minecraft:dirt"));
        // still has a sell price
        assertEquals(0, b.getSellPrice("minecraft:bedrock").compareTo(new BigDecimal("5")));
    }

    @Test
    void nullMapsAreTreatedAsEmpty() {
        PriceBook b = new PriceBook(null, null, new BigDecimal("10"), new BigDecimal("5"));
        assertEquals(0, b.getBuyPrice("minecraft:dirt").compareTo(new BigDecimal("10")));
        assertTrue(b.isBuyable("minecraft:dirt"));
    }
}
```

- [ ] **Step 2: Run the test, verify it fails to compile/run**

Run: `./gradlew test --tests "savage.commoneconomy.economy.PriceBookTest"`
Expected: FAIL — compilation error `cannot find symbol: class PriceBook`.

- [ ] **Step 3: Implement PriceBook**

Create `src/main/java/savage/commoneconomy/economy/PriceBook.java`:

```java
package savage.commoneconomy.economy;

import savage.commoneconomy.config.ItemPrice;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Pure pricing logic. Resolves buy/sell prices for any item id, applying a
 * global fallback for unlisted items, and enforces the unbuyable blacklist.
 * Contains no Minecraft references so it can be unit tested directly.
 */
public class PriceBook {
    private final Map<String, ItemPrice> prices;
    private final Set<String> unbuyable;
    private final BigDecimal defaultBuy;
    private final BigDecimal defaultSell;

    public PriceBook(Map<String, ItemPrice> prices,
                     java.util.Collection<String> unbuyable,
                     BigDecimal defaultBuy,
                     BigDecimal defaultSell) {
        this.prices = prices != null ? new HashMap<>(prices) : new HashMap<>();
        this.unbuyable = unbuyable != null ? new HashSet<>(unbuyable) : new HashSet<>();
        this.defaultBuy = defaultBuy;
        this.defaultSell = defaultSell;
    }

    public BigDecimal getBuyPrice(String itemId) {
        ItemPrice p = prices.get(itemId);
        if (p != null && p.buy != null) {
            return p.buy;
        }
        return defaultBuy;
    }

    public BigDecimal getSellPrice(String itemId) {
        ItemPrice p = prices.get(itemId);
        if (p != null && p.sell != null) {
            return p.sell;
        }
        return defaultSell;
    }

    public boolean isBuyable(String itemId) {
        return !unbuyable.contains(itemId);
    }
}
```

- [ ] **Step 4: Run the test, verify it passes**

Run: `./gradlew test --tests "savage.commoneconomy.economy.PriceBookTest"`
Expected: `BUILD SUCCESSFUL`, 4 tests passed.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/savage/commoneconomy/economy/PriceBook.java src/test/java/savage/commoneconomy/economy/PriceBookTest.java
git commit -m "feat: add PriceBook pricing logic with unit tests"
```

---

## Task 4: Rewrite WorthConfig (buy/sell prices + unbuyable list)

**Files:**
- Modify: `src/main/java/savage/commoneconomy/config/WorthConfig.java`

> Compilation note: `EconomyManager` still references the old `Map<String, BigDecimal> itemPrices` after this change, so the project will NOT compile until Task 6. That is expected.

- [ ] **Step 1: Replace the file contents**

Replace the entire contents of `src/main/java/savage/commoneconomy/config/WorthConfig.java` with:

```java
package savage.commoneconomy.config;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * worth.json: per-item buy/sell prices plus the unbuyable blacklist.
 * Items not present in itemPrices use the default buy/sell prices from
 * EconomyConfig. Items in unbuyable cannot be bought (but can still be sold).
 */
public class WorthConfig {
    public Map<String, ItemPrice> itemPrices = new LinkedHashMap<>();
    public List<String> unbuyable = new ArrayList<>();

    public WorthConfig() {
        // A few curated examples; admins edit worth.json to expand this.
        itemPrices.put("minecraft:diamond", new ItemPrice(BigDecimal.valueOf(100), BigDecimal.valueOf(60)));
        itemPrices.put("minecraft:iron_ingot", new ItemPrice(BigDecimal.valueOf(20), BigDecimal.valueOf(12)));
        itemPrices.put("minecraft:apple", new ItemPrice(BigDecimal.valueOf(10), BigDecimal.valueOf(5)));

        // Items that must never be purchasable (still sellable).
        unbuyable.add("minecraft:bedrock");
        unbuyable.add("minecraft:spawner");
        unbuyable.add("minecraft:command_block");
        unbuyable.add("minecraft:chain_command_block");
        unbuyable.add("minecraft:repeating_command_block");
        unbuyable.add("minecraft:barrier");
        unbuyable.add("minecraft:light");
        unbuyable.add("minecraft:structure_block");
        unbuyable.add("minecraft:structure_void");
        unbuyable.add("minecraft:jigsaw");
        unbuyable.add("minecraft:end_portal_frame");
    }
}
```

- [ ] **Step 2: Verify the file is syntactically valid (compile deferred)**

Do not run a full build yet (project won't compile until Task 6). Visually confirm the file uses `ItemPrice` and imports are present.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/savage/commoneconomy/config/WorthConfig.java
git commit -m "feat: WorthConfig stores buy/sell prices and unbuyable list"
```

---

## Task 5: Slim down EconomyConfig

**Files:**
- Modify: `src/main/java/savage/commoneconomy/config/EconomyConfig.java`

> Compilation note: removing these fields breaks `EconomyManager`, `EconomyCommands`, and `SqliteStorage` references until Tasks 6–9 land. Expected.

- [ ] **Step 1: Replace the file contents**

Replace the entire contents of `src/main/java/savage/commoneconomy/config/EconomyConfig.java` with:

```java
package savage.commoneconomy.config;

import java.math.BigDecimal;

public class EconomyConfig {
    public BigDecimal defaultBalance = BigDecimal.valueOf(1000);
    public String currencySymbol = "$";

    // Fallback prices for items not listed in worth.json.
    public BigDecimal defaultBuyPrice = BigDecimal.valueOf(10);
    public BigDecimal defaultSellPrice = BigDecimal.valueOf(5);

    // Percent of deposited emerald value burned as a fee (0-100). 20 = keep 80%.
    public int depositFeePercent = 20;

    public StorageConfig storage = new StorageConfig();

    /**
     * SQLite-only storage settings. The database file name is fixed inside
     * savdbcore ("economy_data.sqlite"); these fields tune table naming and
     * the connection pool.
     */
    public static class StorageConfig {
        public String tablePrefix = "savs_eco_";
        public int poolSize = 10;
        public long connectionTimeout = 30000;
        public long idleTimeout = 600000;
    }
}
```

Note what was removed: `symbolBeforeAmount`, `enableSellCommands`, `enableChestShops`, `RedisConfig redis` + the `RedisConfig` class, the MySQL/Postgres fields (`host`, `port`, `database`, `user`, `password`) and `type`, the `NotificationMode` enum + its two fields, and the `StorageType` enum.

- [ ] **Step 2: Commit (compile deferred)**

```bash
git add src/main/java/savage/commoneconomy/config/EconomyConfig.java
git commit -m "refactor: strip EconomyConfig toggles, redis, and non-SQLite storage fields"
```

---

## Task 6: Rewrite EconomyManager (SQLite-only, PriceBook, no Redis)

**Files:**
- Modify: `src/main/java/savage/commoneconomy/EconomyManager.java`

This is the central change. Apply each edit below.

- [ ] **Step 1: Fix imports**

In `src/main/java/savage/commoneconomy/EconomyManager.java`, replace the import line:

```java
import savage.commoneconomy.storage.JsonStorage;
```

with:

```java
import savage.commoneconomy.economy.PriceBook;
import savage.commoneconomy.storage.SqliteStorage;
```

- [ ] **Step 2: Replace `initStorage()` with a SQLite-only version**

Replace the entire `initStorage()` method (currently lines ~68–115) with:

```java
    public void initStorage() {
        if (storage != null) return; // Already initialized

        int maxRetries = 10;
        int attempt = 0;

        while (attempt < maxRetries) {
            try {
                storage = new SqliteStorage(this, config.storage.tablePrefix);
                savage.commoneconomy.SavsCommonEconomy.LOGGER.info("Economy Storage initialized successfully: SQLITE");
                break;
            } catch (Exception e) {
                attempt++;
                savage.commoneconomy.SavsCommonEconomy.LOGGER.warn(
                        "Failed to initialize economy storage (Attempt " + attempt + "/" + maxRetries + "). Retrying in 2 seconds...", e);

                if (attempt >= maxRetries) {
                    savage.commoneconomy.SavsCommonEconomy.LOGGER.error(
                            "Could not initialize economy storage after " + maxRetries + " attempts.");
                    throw new RuntimeException("Failed to initialize SQLite economy storage", e);
                }

                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }
```

- [ ] **Step 3: Remove Redis from `setBalance(uuid, amount, publishToRedis)`**

Replace the method body (currently lines ~185–191) with:

```java
    public void setBalance(UUID uuid, BigDecimal amount, boolean publishToRedis) {
        storage.setBalance(uuid, amount);
        accountCache.invalidate(uuid);
    }
```

(The `publishToRedis` parameter is kept so existing callers still compile; it is now ignored. Same applies in the next two steps.)

- [ ] **Step 4: Remove the Redis block in `addBalance(...)`**

In `addBalance(UUID uuid, BigDecimal amount, boolean publishToRedis)`, delete these lines (inside the success branch):

```java
                if (publishToRedis && config.redis.enabled) {
                    savage.commoneconomy.util.RedisManager.getInstance().publishBalanceUpdate(uuid, current.add(amount));
                }
```

- [ ] **Step 5: Remove the Redis block in `removeBalance(...)`**

In `removeBalance(UUID uuid, BigDecimal amount, boolean publishToRedis)`, delete these lines (inside the success branch):

```java
                    if (publishToRedis && config.redis.enabled) {
                        savage.commoneconomy.util.RedisManager.getInstance().publishBalanceUpdate(uuid, current.subtract(amount));
                    }
```

- [ ] **Step 6: Make `format()` always put the symbol before the amount**

Replace the `format` method (currently lines ~344–350) with:

```java
    public String format(BigDecimal amount) {
        return config.currencySymbol + amount.toString();
    }
```

- [ ] **Step 7: Replace the sell-system section with PriceBook-backed methods**

Replace everything from the `// Sell system support` comment through the end of `loadWorthConfig()` (currently lines ~357–397) with:

```java
    // Pricing support
    private WorthConfig worthConfig;
    private PriceBook priceBook;

    private PriceBook priceBook() {
        if (priceBook == null) {
            if (worthConfig == null) {
                loadWorthConfig();
            }
            priceBook = new PriceBook(
                    worthConfig.itemPrices,
                    worthConfig.unbuyable,
                    config.defaultBuyPrice,
                    config.defaultSellPrice);
        }
        return priceBook;
    }

    public BigDecimal getBuyPrice(String itemId) {
        return priceBook().getBuyPrice(itemId);
    }

    public BigDecimal getSellPrice(String itemId) {
        return priceBook().getSellPrice(itemId);
    }

    public boolean isBuyable(String itemId) {
        return priceBook().isBuyable(itemId);
    }

    /** The physical currency item id. Emerald is currency, not a tradeable good. */
    public static final String CURRENCY_ITEM_ID = "minecraft:emerald";

    public boolean isCurrencyItem(String itemId) {
        return CURRENCY_ITEM_ID.equals(itemId);
    }

    /** All curated item prices (for /worth list). Excludes fallback-only items. */
    public Map<String, ItemPrice> getAllItemPrices() {
        if (worthConfig == null) {
            loadWorthConfig();
        }
        return new java.util.LinkedHashMap<>(worthConfig.itemPrices);
    }

    private void loadWorthConfig() {
        Path worthPath = FabricLoader.getInstance().getConfigDir().resolve("savs-common-economy").resolve("worth.json");
        File worthFile = worthPath.toFile();

        if (!worthFile.exists()) {
            this.worthConfig = new WorthConfig();
            try (FileWriter writer = new FileWriter(worthFile)) {
                gson.toJson(this.worthConfig, writer);
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            try (FileReader reader = new FileReader(worthFile)) {
                this.worthConfig = gson.fromJson(reader, WorthConfig.class);
            } catch (IOException e) {
                e.printStackTrace();
                this.worthConfig = new WorthConfig();
            }
        }
    }
```

This removes `isSellEnabled()` and the old single-price `getItemPrice(...)`. Their callers are updated in Tasks 9–10.

- [ ] **Step 8: Add the ItemPrice import**

Near the other config imports at the top of the file, add:

```java
import savage.commoneconomy.config.ItemPrice;
```

(`EconomyConfig` and `WorthConfig` imports already exist.)

- [ ] **Step 9: Commit (compile still deferred — shop/Redis files referenced elsewhere)**

```bash
git add src/main/java/savage/commoneconomy/EconomyManager.java
git commit -m "refactor: EconomyManager uses SQLite-only storage and PriceBook"
```

---

## Task 7: Delete the shop package and rewire the main initializer

**Files:**
- Delete: all 9 files in `src/main/java/savage/commoneconomy/shop/`
- Modify: `src/main/java/savage/commoneconomy/SavsCommonEconomy.java`

- [ ] **Step 1: Delete the shop package**

```bash
git rm src/main/java/savage/commoneconomy/shop/Shop.java \
       src/main/java/savage/commoneconomy/shop/ShopManager.java \
       src/main/java/savage/commoneconomy/shop/ShopCommands.java \
       src/main/java/savage/commoneconomy/shop/ShopInteractionManager.java \
       src/main/java/savage/commoneconomy/shop/ShopSignHelper.java \
       src/main/java/savage/commoneconomy/shop/ShopStockCalculator.java \
       src/main/java/savage/commoneconomy/shop/ShopTransactionHandler.java \
       src/main/java/savage/commoneconomy/shop/ShopTransactionManager.java \
       src/main/java/savage/commoneconomy/shop/ShopType.java
```

- [ ] **Step 2: Replace SavsCommonEconomy.java**

Replace the entire contents of `src/main/java/savage/commoneconomy/SavsCommonEconomy.java` with:

```java
package savage.commoneconomy;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import savage.commoneconomy.command.BuyCommand;
import savage.commoneconomy.command.DebugCommands;
import savage.commoneconomy.command.DepositCommand;
import savage.commoneconomy.command.EconomyCommands;
import savage.commoneconomy.command.LogCommand;
import savage.commoneconomy.command.SellCommands;

public class SavsCommonEconomy implements ModInitializer {
	public static final String MOD_ID = "savs-common-economy";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("Initializing Savs Common Economy...");

		eu.pb4.common.economy.api.CommonEconomy.register("savs_common_economy",
				savage.commoneconomy.integration.SavsEconomyProvider.INSTANCE);

		LOGGER.info("Savs Common Economy initialized.");

		// Register commands
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			EconomyCommands.register(dispatcher);
			SellCommands.register(dispatcher);
			BuyCommand.register(dispatcher);
			DepositCommand.register(dispatcher);
			LogCommand.register(dispatcher);
			DebugCommands.register(dispatcher);
		});

		// Load economy data when server starts
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			EconomyManager.getInstance().initStorage();
			EconomyManager.getInstance().setServer(server);
			EconomyManager.getInstance().load();
		});

		// Save economy data when server stops
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			EconomyManager.getInstance().save();
		});

		// Create account on join
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (!EconomyManager.getInstance().hasAccount(handler.player.getUuid())) {
				EconomyManager.getInstance().createAccount(handler.player.getUuid(),
						handler.player.getName().getString());
			}
		});
	}
}
```

This removes: the `enableChestShops` command branch, the shop `ShopManager` lifecycle load/save, the Redis init block, the `UseBlockCallback` sign handler, the `ServerMessageEvents.ALLOW_CHAT_MESSAGE` listener, the `PlayerBlockBreakEvents.BEFORE` protection, the `ServerTickEvents.END_SERVER_TICK` cleanup/sign loops, **and the right-click bank-note `UseItemCallback` redemption** (emeralds + `/deposit` replace it). It keeps the provider, command registration (now including `/buy` and `/deposit`), the storage lifecycle, and account-on-join.

- [ ] **Step 3: Commit (compile deferred — Redis/storage files still present and referenced)**

```bash
git add src/main/java/savage/commoneconomy/SavsCommonEconomy.java
git commit -m "refactor: remove chest-shop system and Redis init from initializer"
```

---

## Task 8: Delete unused storage backends, Redis layer, and the Redis dependency

**Files:**
- Delete: `storage/JsonStorage.java`, `storage/MysqlStorage.java`, `storage/PostgresStorage.java`
- Delete: `util/RedisManager.java`, `util/RedisBackend.java`, `util/RealRedisBackend.java`
- Modify: `build.gradle`

- [ ] **Step 1: Delete the files**

```bash
git rm src/main/java/savage/commoneconomy/storage/JsonStorage.java \
       src/main/java/savage/commoneconomy/storage/MysqlStorage.java \
       src/main/java/savage/commoneconomy/storage/PostgresStorage.java \
       src/main/java/savage/commoneconomy/util/RedisManager.java \
       src/main/java/savage/commoneconomy/util/RedisBackend.java \
       src/main/java/savage/commoneconomy/util/RealRedisBackend.java
```

- [ ] **Step 2: Remove the Redis dependency from build.gradle**

In `build.gradle`, delete these two lines (around line 46–47):

```gradle
	// Savs Redis Lib (Soft Dependency)
	modImplementation "com.github.xSaVageAU:Savs-Redis-Lib:1.0.0"
```

- [ ] **Step 3: Commit (compile deferred — EconomyCommands/SellCommands still reference removed APIs)**

```bash
git add -A
git commit -m "refactor: delete JSON/MySQL/Postgres storage and Redis layer"
```

---

## Task 9: Clean Redis + notification-mode out of EconomyCommands

**Files:**
- Modify: `src/main/java/savage/commoneconomy/command/EconomyCommands.java`

The goal: every `RedisManager.getInstance().publishTransaction(...)` block is removed, and `sendCommandFeedback` plus the inline `NotificationMode` branches become plain chat feedback.

- [ ] **Step 1: Simplify `sendCommandFeedback`**

Replace the `sendCommandFeedback` method (currently lines ~154–169) with:

```java
    private static void sendCommandFeedback(CommandContext<ServerCommandSource> context, String message, boolean broadcastToOps) {
        context.getSource().sendFeedback(() -> Text.literal(message), broadcastToOps);
    }
```

- [ ] **Step 2: Strip Redis + notification branching from `pay`**

In `pay(...)`, the success branch currently locates the target player and runs notification-mode `if/else` blocks plus two `RedisManager...publishTransaction(...)` try/catch blocks. Replace everything from the line:

```java
            ServerPlayerEntity target = context.getSource().getServer().getPlayerManager().getPlayer(targetUUID);
            if (target != null) {
```

down to the line immediately before:

```java
            savage.commoneconomy.util.TransactionLogger.log("PAY", sourcePlayer.getName().getString(), displayName, amount, "Payment");
```

with:

```java
            ServerPlayerEntity target = context.getSource().getServer().getPlayerManager().getPlayer(targetUUID);
            if (target != null) {
                target.sendMessage(Text.literal("Received " + formattedAmount + " from " + sourcePlayer.getName().getString()), false);
            }
```

- [ ] **Step 3: Strip Redis + notification branching from `giveMoney`**

In `giveMoney(...)`, replace everything from:

```java
            ServerPlayerEntity target = context.getSource().getServer().getPlayerManager().getPlayer(targetUUID);
            if (target != null) {
```

down to the line immediately before:

```java
            savage.commoneconomy.util.TransactionLogger.log("ADMIN_GIVE", context.getSource().getName(), displayName, amount, "Admin Gift");
```

with:

```java
            ServerPlayerEntity target = context.getSource().getServer().getPlayerManager().getPlayer(targetUUID);
            if (target != null) {
                target.sendMessage(Text.literal("Received " + formattedAmount + " (Admin Gift)"), false);
            }
```

- [ ] **Step 4: Strip the Redis block from `takeMoney`**

In `takeMoney(...)`, delete the entire try/catch block that begins with the comment `// Publish Redis update to invalidate caches (silent)` and contains `RedisManager.getInstance().publishTransaction(...)`. Keep the `sendCommandFeedback(...)` call and the `TransactionLogger.log("ADMIN_TAKE", ...)` line.

- [ ] **Step 5: Strip notification + Redis from `setMoney`**

In `setMoney(...)`, replace everything from:

```java
        ServerPlayerEntity target = context.getSource().getServer().getPlayerManager().getPlayer(targetUUID);
        if (target != null) {
```

down to the line immediately before:

```java
        savage.commoneconomy.util.TransactionLogger.log("ADMIN_SET", context.getSource().getName(), displayName, amount, "Set Balance");
```

with:

```java
        ServerPlayerEntity target = context.getSource().getServer().getPlayerManager().getPlayer(targetUUID);
        if (target != null) {
            target.sendMessage(Text.literal("Your balance has been set to " + formattedAmount), false);
        }
```

- [ ] **Step 6: Strip notification + Redis from `resetMoney`**

In `resetMoney(...)`, replace everything from:

```java
        ServerPlayerEntity target = context.getSource().getServer().getPlayerManager().getPlayer(targetUUID);
        if (target != null) {
```

down to (and including) the final Redis try/catch block that ends just before `return 1;`, with:

```java
        ServerPlayerEntity target = context.getSource().getServer().getPlayerManager().getPlayer(targetUUID);
        if (target != null) {
            target.sendMessage(Text.literal("Your balance has been reset to " + formattedAmount), false);
        }
```

- [ ] **Step 7: Rewrite `/withdraw` to give vanilla emeralds (1:1, no fee)**

Replace the entire `withdraw(...)` method (currently lines ~431–460) with:

```java
    private static int withdraw(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        double amountDouble = DoubleArgumentType.getDouble(context, "amount");
        BigDecimal amount = BigDecimal.valueOf(amountDouble);

        // 1 emerald = $1. Withdraw whole emeralds only.
        int emeralds = amount.setScale(0, java.math.RoundingMode.DOWN).intValueExact();
        if (emeralds <= 0) {
            context.getSource().sendError(Text.literal("Withdraw at least 1."));
            return 0;
        }
        BigDecimal cost = BigDecimal.valueOf(emeralds);

        if (EconomyManager.getInstance().removeBalance(player.getUuid(), cost)) {
            int remaining = emeralds;
            int maxStack = new net.minecraft.item.ItemStack(net.minecraft.item.Items.EMERALD).getMaxCount();
            while (remaining > 0) {
                int give = Math.min(remaining, maxStack);
                player.getInventory().offerOrDrop(new net.minecraft.item.ItemStack(net.minecraft.item.Items.EMERALD, give));
                remaining -= give;
            }
            sendCommandFeedback(context, "Withdrew " + EconomyManager.getInstance().format(cost) + " as " + emeralds + " emeralds.", false);
            savage.commoneconomy.util.TransactionLogger.log("WITHDRAW", player.getName().getString(), "Emeralds", cost, "Withdrawal");
            return 1;
        } else {
            context.getSource().sendError(Text.literal("Insufficient funds."));
            return 0;
        }
    }
```

This removes the paper-note `ItemStack`/NBT/`CUSTOM_NAME` creation entirely.

- [ ] **Step 8: Confirm no Redis references remain in the file**

Run: `grep -n "Redis\|NotificationMode\|commandNotificationMode\|EconomyBankNote" src/main/java/savage/commoneconomy/command/EconomyCommands.java`
Expected: no output.

- [ ] **Step 9: Commit (compile deferred until SellCommands fixed)**

```bash
git add src/main/java/savage/commoneconomy/command/EconomyCommands.java
git commit -m "refactor: chat-only feedback, Redis removed, /withdraw gives emeralds"
```

---

## Task 10: Update SellCommands (always on; buy+sell in /worth; sell price)

**Files:**
- Modify: `src/main/java/savage/commoneconomy/command/SellCommands.java`

- [ ] **Step 1: Remove the sell-enabled guard**

In `register(...)`, delete this line (currently line 23):

```java
        if (!EconomyManager.getInstance().isSellEnabled()) return;
```

- [ ] **Step 2: Replace `getItemPrice` usages with `getSellPrice` for the sell paths**

In `checkAllWorth`, `sellHand`, and `sellAll`, replace each occurrence of:

```java
        BigDecimal price = EconomyManager.getInstance().getItemPrice(itemId);
```

with:

```java
        BigDecimal price = EconomyManager.getInstance().getSellPrice(itemId);
```

(There is one such line in each of those three methods.)

- [ ] **Step 2b: Reject emerald in the sell paths**

In both `sellHand` and `sellAll`, immediately after the line that computes
`String itemId = Registries.ITEM.getId(...).toString();`, insert:

```java
        if (EconomyManager.getInstance().isCurrencyItem(itemId)) {
            context.getSource().sendError(Text.literal("Emeralds are currency — use /deposit instead."));
            return 0;
        }
```

- [ ] **Step 3: Update `checkHandWorth` to show both buy and sell**

Replace the body of `checkHandWorth` (from `String itemId = ...` to the closing `return 1;`) with:

```java
        String itemId = Registries.ITEM.getId(stack.getItem()).toString();
        BigDecimal sell = EconomyManager.getInstance().getSellPrice(itemId);
        BigDecimal buy = EconomyManager.getInstance().getBuyPrice(itemId);
        boolean buyable = EconomyManager.getInstance().isBuyable(itemId);

        BigDecimal stackValue = sell.multiply(BigDecimal.valueOf(stack.getCount()));
        String buyText = buyable ? EconomyManager.getInstance().format(buy) : "not buyable";
        context.getSource().sendFeedback(() -> Text.literal(
                stack.getCount() + "x " + itemId
                        + " | Sell: " + EconomyManager.getInstance().format(sell) + " each (total " + EconomyManager.getInstance().format(stackValue) + ")"
                        + " | Buy: " + buyText + " each"), false);
        return 1;
```

- [ ] **Step 4: Update `checkItemWorth` to show both buy and sell**

Replace the body of `checkItemWorth` (from `String itemId = ...` to the closing `return 1;`) with:

```java
        String itemId = StringArgumentType.getString(context, "item");
        BigDecimal sell = EconomyManager.getInstance().getSellPrice(itemId);
        BigDecimal buy = EconomyManager.getInstance().getBuyPrice(itemId);
        boolean buyable = EconomyManager.getInstance().isBuyable(itemId);
        String buyText = buyable ? EconomyManager.getInstance().format(buy) : "not buyable";
        context.getSource().sendFeedback(() -> Text.literal(
                itemId + " | Sell: " + EconomyManager.getInstance().format(sell) + " each | Buy: " + buyText + " each"), false);
        return 1;
```

(Removes the old "cannot be sold" early-return, since every item now has a fallback sell price.)

- [ ] **Step 5: Update `listWorth` to print buy/sell pairs**

Replace the body of `listWorth` (from `Map<String, BigDecimal> prices = ...` to the closing `return 1;`) with:

```java
        Map<String, savage.commoneconomy.config.ItemPrice> prices = EconomyManager.getInstance().getAllItemPrices();
        if (prices.isEmpty()) {
            context.getSource().sendFeedback(() -> Text.literal("No curated item prices are configured."), false);
            return 1;
        }

        context.getSource().sendFeedback(() -> Text.literal("Curated Item Prices (buy / sell):"), false);
        for (Map.Entry<String, savage.commoneconomy.config.ItemPrice> entry : prices.entrySet()) {
            savage.commoneconomy.config.ItemPrice p = entry.getValue();
            String buy = p.buy != null ? EconomyManager.getInstance().format(p.buy) : "-";
            String sell = p.sell != null ? EconomyManager.getInstance().format(p.sell) : "-";
            context.getSource().sendFeedback(() -> Text.literal("- " + entry.getKey() + ": " + buy + " / " + sell), false);
        }
        return 1;
```

- [ ] **Step 6: Remove the now-unused `Map<String, BigDecimal>` import if present**

The `import java.util.Map;` line stays (still used). No import changes needed; `Item`/`Identifier` imports may now be unused but are harmless. Leave them.

- [ ] **Step 7: Commit (compile deferred until /buy added — `BuyCommand` referenced by main)**

```bash
git add src/main/java/savage/commoneconomy/command/SellCommands.java
git commit -m "feat: SellCommands always on; /worth shows buy and sell prices"
```

---

## Task 11: Add the /buy and /deposit commands and reach first green build

**Files:**
- Create: `src/main/java/savage/commoneconomy/command/BuyCommand.java`
- Create: `src/main/java/savage/commoneconomy/command/DepositCommand.java`

- [ ] **Step 1: Create BuyCommand**

Create `src/main/java/savage/commoneconomy/command/BuyCommand.java`:

```java
package savage.commoneconomy.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.command.CommandSource;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import savage.commoneconomy.EconomyManager;

import java.math.BigDecimal;

public class BuyCommand {

    private static final SuggestionProvider<ServerCommandSource> ITEM_SUGGESTIONS = (context, builder) ->
            CommandSource.suggestMatching(
                    EconomyManager.getInstance().getAllItemPrices().keySet().stream()
                            .filter(id -> EconomyManager.getInstance().isBuyable(id))
                            .toList(),
                    builder);

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("buy")
                .requires(source -> savage.commoneconomy.util.PermissionsHelper.check(source, "savscommoneconomy.command.buy", true))
                .then(CommandManager.argument("item", StringArgumentType.string())
                        .suggests(ITEM_SUGGESTIONS)
                        .then(CommandManager.argument("amount", IntegerArgumentType.integer(1))
                                .executes(BuyCommand::buy))));
    }

    private static int buy(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        String itemId = StringArgumentType.getString(context, "item");
        int amount = IntegerArgumentType.getInteger(context, "amount");

        Identifier id = Identifier.tryParse(itemId);
        if (id == null || !Registries.ITEM.containsId(id)) {
            context.getSource().sendError(Text.literal("Unknown item: " + itemId));
            return 0;
        }

        if (EconomyManager.getInstance().isCurrencyItem(itemId)) {
            context.getSource().sendError(Text.literal("Emeralds are currency — use /withdraw instead."));
            return 0;
        }

        if (!EconomyManager.getInstance().isBuyable(itemId)) {
            context.getSource().sendError(Text.literal("This item cannot be bought."));
            return 0;
        }

        Item item = Registries.ITEM.get(id);
        BigDecimal unitPrice = EconomyManager.getInstance().getBuyPrice(itemId);
        BigDecimal totalCost = unitPrice.multiply(BigDecimal.valueOf(amount));

        if (!EconomyManager.getInstance().removeBalance(player.getUuid(), totalCost)) {
            context.getSource().sendError(Text.literal("Insufficient funds. Cost: "
                    + EconomyManager.getInstance().format(totalCost)));
            return 0;
        }

        int remaining = amount;
        int maxStack = new ItemStack(item).getMaxCount(); // 1.20.5+: stack size is a data component, not Item.getMaxCount()
        while (remaining > 0) {
            int give = Math.min(remaining, maxStack);
            player.getInventory().offerOrDrop(new ItemStack(item, give));
            remaining -= give;
        }

        final int boughtAmount = amount;
        context.getSource().sendFeedback(() -> Text.literal("Bought " + boughtAmount + "x " + itemId
                + " for " + EconomyManager.getInstance().format(totalCost)), false);
        savage.commoneconomy.util.TransactionLogger.log("COMMAND_BUY", player.getName().getString(), "Server",
                totalCost, "Bought " + boughtAmount + "x " + itemId);
        return 1;
    }
}
```

- [ ] **Step 2: Create DepositCommand**

Create `src/main/java/savage/commoneconomy/command/DepositCommand.java`:

```java
package savage.commoneconomy.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import savage.commoneconomy.EconomyManager;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class DepositCommand {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("deposit")
                .requires(source -> savage.commoneconomy.util.PermissionsHelper.check(source, "savscommoneconomy.command.deposit", true))
                .then(CommandManager.literal("all")
                        .executes(ctx -> deposit(ctx, Integer.MAX_VALUE)))
                .then(CommandManager.argument("amount", IntegerArgumentType.integer(1))
                        .executes(ctx -> deposit(ctx, IntegerArgumentType.getInteger(ctx, "amount")))));
    }

    private static int deposit(CommandContext<ServerCommandSource> context, int requested) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();

        // Count emeralds held.
        int held = 0;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.getItem() == Items.EMERALD) {
                held += stack.getCount();
            }
        }

        if (held <= 0) {
            context.getSource().sendError(Text.literal("You have no emeralds to deposit."));
            return 0;
        }

        int toDeposit = Math.min(requested, held);

        // Remove toDeposit emeralds from inventory.
        int remaining = toDeposit;
        for (int i = 0; i < player.getInventory().size() && remaining > 0; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.getItem() == Items.EMERALD) {
                int take = Math.min(remaining, stack.getCount());
                stack.decrement(take);
                remaining -= take;
            }
        }

        // Gross value = toDeposit (1:1). Net = gross * (1 - fee%).
        int feePercent = Math.max(0, Math.min(100, EconomyManager.getInstance().getConfig().depositFeePercent));
        BigDecimal gross = BigDecimal.valueOf(toDeposit);
        BigDecimal net = gross
                .multiply(BigDecimal.valueOf(100 - feePercent))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.DOWN);
        BigDecimal fee = gross.subtract(net);

        EconomyManager.getInstance().addBalance(player.getUuid(), net);

        final int depositedFinal = toDeposit;
        context.getSource().sendFeedback(() -> Text.literal(
                "Deposited " + depositedFinal + " emeralds → " + EconomyManager.getInstance().format(net)
                        + " (fee " + feePercent + "%: " + EconomyManager.getInstance().format(fee) + ")"), false);
        savage.commoneconomy.util.TransactionLogger.log("DEPOSIT", player.getName().getString(), "Emeralds", net,
                depositedFinal + " emeralds, " + feePercent + "% fee");
        return 1;
    }
}
```

- [ ] **Step 3: Run the full build (first expected green)**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`. The `PriceBookTest` runs as part of `build` and passes. There must be no `cannot find symbol` errors referencing Shop*, Redis*, JsonStorage, MysqlStorage, PostgresStorage, `getItemPrice`, `isSellEnabled`, `symbolBeforeAmount`, or `NotificationMode`.

- [ ] **Step 4: If the build reports any lingering references, fix them**

Search for stragglers and remove/repair each hit:

Run: `grep -rn "Redis\|ShopManager\|JsonStorage\|MysqlStorage\|PostgresStorage\|getItemPrice\|isSellEnabled\|symbolBeforeAmount\|NotificationMode\|enableChestShops\|enableSellCommands\|EconomyBankNote" src/main/java`
Expected: no output. Fix anything that appears, then re-run `./gradlew build`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/savage/commoneconomy/command/BuyCommand.java src/main/java/savage/commoneconomy/command/DepositCommand.java
git commit -m "feat: add /buy and /deposit commands, complete economy overhaul build"
```

---

## Task 12: In-game smoke test

**Files:** none (manual verification on a dev server).

- [ ] **Step 1: Launch the dev server**

Run: `./gradlew runServer`
Expected: server starts, log shows `Economy Storage initialized successfully: SQLITE` and `Savs Common Economy initialized.` with no shop/Redis errors.

- [ ] **Step 2: Verify config files**

Confirm `run/config/savs-common-economy/config.json` contains `defaultBuyPrice`/`defaultSellPrice`/`depositFeePercent` and no `redis`/`enableChestShops` keys, and `worth.json` contains `itemPrices` with `buy`/`sell` and an `unbuyable` list.

- [ ] **Step 3: Exercise commands in-game (creative or op)**

Verify each, watching for chat feedback:
- `/worth minecraft:diamond` → shows Sell and Buy prices.
- `/worth minecraft:dirt` → shows the fallback prices.
- `/buy minecraft:diamond 5` → balance drops by 5× buy price, 5 diamonds appear.
- `/buy minecraft:bedrock 1` → rejected ("cannot be bought").
- `/buy minecraft:dirt 64` → uses fallback price, items delivered.
- `/buy minecraft:emerald 1` → rejected ("Emeralds are currency").
- Hold an item, `/sell` and `/sell all` → balance rises, items consumed.
- Hold emeralds, `/sell` → rejected ("Emeralds are currency").
- `/withdraw 50` → receive **50 vanilla emeralds**, balance drops by $50.
- `/deposit all` (holding those 50 emeralds) → balance rises by **$40** (20% fee), emeralds consumed; feedback shows fee.
- `/deposit 10` (with more held) → deposits exactly 10 emeralds.
- `/pay <player> 10`, `/givemoney`, `/takemoney`, `/setmoney`, `/resetmoney`, `/bal`, `/baltop` → all give chat feedback.
- `/ecodebug verify` → reports DATA CONSISTENCY CHECK: PASSED on SQLite.
- Confirm `/shop` does not exist (unknown command).

- [ ] **Step 4: Verify persistence**

Stop the server (`/stop`), restart (`./gradlew runServer`), check a modified balance with `/bal <player>` persisted.

- [ ] **Step 5: Commit any fixes**

If smoke testing surfaced bugs, fix them with focused commits referencing the failing check.

---

## Self-Review Notes (for the implementer)

- `removeBalance`/`addBalance`/`setBalance` keep their `publishToRedis` parameter purely so existing call sites compile; the value is ignored. A later cleanup pass could drop the parameter, but that is out of scope here.
- `Identifier.tryParse` + `Registries.ITEM.containsId` guards `/buy` against typo item ids; `/sell`/`/worth` already operate on real held items or accept arbitrary strings (fallback-priced).
- The SQLite DB filename (`economy_data.sqlite`) is fixed inside savdbcore and is not configurable here, by design.
