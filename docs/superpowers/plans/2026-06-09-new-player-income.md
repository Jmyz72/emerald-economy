# New-Player Income Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add four configurable, restart-friendly income sources — `/daily`, mob-kill rewards, session-based playtime income, and a bigger configurable start — so new players earn money without the late-game emerald farm.

**Architecture:** A new `rewards/` package holds the income logic (`DailyRewards`, `MobKillRewards`, `PlaytimeIncome`) plus a `RewardsConfig` block nested in `EconomyConfig`; `/daily` lives in `command/` with its siblings. Every payout flows through `EconomyManager.addBalance` + `TransactionLogger.log`. `/eco reload` is extended to re-read `config.json` so the reward knobs are live-tunable.

**Tech Stack:** Java 21, Fabric Loom (MC 1.21.11, loader 0.19.3), Brigadier commands, Fabric `ServerLivingEntityEvents` / `ServerPlayConnectionEvents`, Gson JSON, JUnit 5. Spec: `docs/superpowers/specs/2026-06-09-new-player-income-design.md`.

---

## Verification harness (read first)

Pure logic (cooldown math, duration formatting, session-minute flooring) is unit-tested with JUnit, matching the existing `economy/PriceBookTest` style. The Minecraft-coupled behavior (event listeners, command execution, chat feedback, persistence paths) cannot be unit-tested without a server bootstrap, so it is verified by a green build plus the in-game smoke-test checklist at the end.

**`JAVA_HOME` must be set first** — Java is not on PATH. Build (compiles main + runs all unit tests):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat build
```

Run a single new test class (the TDD red/green step):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat test --tests "savage.emeraldeconomy.rewards.DailyRewardsTest"
```

Expected on success: `BUILD SUCCESSFUL`. Every commit message ends with the trailer:

```
Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
```

---

## File structure

**Create:**
- `src/main/java/savage/emeraldeconomy/rewards/DailyRewards.java` — `rewards.json` store + pure cooldown math + claim recording.
- `src/main/java/savage/emeraldeconomy/rewards/MobKillRewards.java` — `AFTER_DEATH` listener + hostile classification.
- `src/main/java/savage/emeraldeconomy/rewards/PlaytimeIncome.java` — JOIN/DISCONNECT session income + pure minute math.
- `src/main/java/savage/emeraldeconomy/command/DailyCommand.java` — `/daily` command + pure duration formatting.
- `src/test/java/savage/emeraldeconomy/rewards/DailyRewardsTest.java`
- `src/test/java/savage/emeraldeconomy/command/DailyCommandTest.java`
- `src/test/java/savage/emeraldeconomy/rewards/PlaytimeIncomeTest.java`

**Modify:**
- `src/main/java/savage/emeraldeconomy/config/EconomyConfig.java` — add nested `RewardsConfig`.
- `src/main/java/savage/emeraldeconomy/EconomyManager.java` — add `reloadConfig()`.
- `src/main/java/savage/emeraldeconomy/command/EcoCommands.java` — reload also reloads config.
- `src/main/java/savage/emeraldeconomy/EmeraldEconomy.java` — register `/daily`, the two reward listeners, and the `DailyRewards` load.

Task order: config plumbing (Task 1) → daily store (Task 2) → daily command + wiring (Task 3) → mob kills (Task 4) → playtime (Task 5). Each task ends green.

---

### Task 1: Rewards config block + `/eco reload` re-reads config

**Files:**
- Modify: `src/main/java/savage/emeraldeconomy/config/EconomyConfig.java`
- Modify: `src/main/java/savage/emeraldeconomy/EconomyManager.java`
- Modify: `src/main/java/savage/emeraldeconomy/command/EcoCommands.java`

- [ ] **Step 1: Add the `RewardsConfig` block to `EconomyConfig`**

Replace the entire contents of `src/main/java/savage/emeraldeconomy/config/EconomyConfig.java` with:

```java
package savage.emeraldeconomy.config;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

public class EconomyConfig {
    public BigDecimal defaultBalance = BigDecimal.valueOf(25000);
    public String currencySymbol = "$";

    // Percent of deposited emerald value burned as a fee (0-100). 20 = keep 80%.
    public int depositFeePercent = 20;

    public StorageConfig storage = new StorageConfig();

    public RewardsConfig rewards = new RewardsConfig();

    /**
     * SQLite-only storage settings. The database file name is fixed inside
     * savdbcore ("economy_data.sqlite"); these fields tune table naming and
     * the connection pool.
     */
    public static class StorageConfig {
        public String tablePrefix = "emerald_eco_";
        public int poolSize = 10;
        public long connectionTimeout = 30000;
        public long idleTimeout = 600000;
    }

    /**
     * New-player income knobs. All live-tunable via /eco reload. Money fields are
     * BigDecimal (decimals allowed); dailyCooldownHours is a whole-hour count.
     * mobKillRewards is an optional per-entity-id override map (the "tier list"):
     * a listed mob pays its amount; anything else falls back to the flat
     * hostile/passive rate.
     */
    public static class RewardsConfig {
        public BigDecimal dailyAmount = BigDecimal.valueOf(10000);
        public int dailyCooldownHours = 12;
        public BigDecimal mobKillHostile = BigDecimal.valueOf(100);
        public BigDecimal mobKillPassive = BigDecimal.valueOf(50);
        public Map<String, BigDecimal> mobKillRewards = defaultMobKillRewards();
        public BigDecimal playtimePerMinute = BigDecimal.valueOf(50);

        private static Map<String, BigDecimal> defaultMobKillRewards() {
            Map<String, BigDecimal> m = new LinkedHashMap<>();
            m.put("minecraft:ender_dragon", BigDecimal.valueOf(50000));
            m.put("minecraft:wither", BigDecimal.valueOf(25000));
            m.put("minecraft:warden", BigDecimal.valueOf(10000));
            m.put("minecraft:elder_guardian", BigDecimal.valueOf(2000));
            m.put("minecraft:blaze", BigDecimal.valueOf(250));
            return m;
        }
    }
}
```

- [ ] **Step 2: Add `reloadConfig()` to `EconomyManager`**

In `src/main/java/savage/emeraldeconomy/EconomyManager.java`, the `loadConfig()` method ends at its closing brace (immediately before `public void load()`). Insert this method directly after `loadConfig()`'s closing brace:

```java
    /** Re-read config.json into memory so /eco reload retunes reward/economy knobs live. */
    public void reloadConfig() {
        loadConfig();
    }
```

- [ ] **Step 3: Make `/eco reload` reload config too**

In `src/main/java/savage/emeraldeconomy/command/EcoCommands.java`, replace the `reload` method:

```java
    private static int reload(CommandContext<ServerCommandSource> context) {
        EconomyManager.getInstance().reloadConfig();
        EconomyManager.getInstance().reloadPrices();
        int total = EconomyManager.getInstance().getAllItemPrices().size();
        context.getSource().sendFeedback(() -> Text.literal("Reloaded config.json and worth.json ("
                + total + " priced items)."), true);
        return 1;
    }
```

- [ ] **Step 4: Build to verify it compiles and existing tests pass**

Run:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat build
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/savage/emeraldeconomy/config/EconomyConfig.java src/main/java/savage/emeraldeconomy/EconomyManager.java src/main/java/savage/emeraldeconomy/command/EcoCommands.java
git commit -m "feat: add rewards config block and reload config.json on /eco reload

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: `DailyRewards` store + cooldown math (TDD)

The cooldown math is pure and unit-tested; the file IO is verified by build + smoke test.

**Files:**
- Create: `src/main/java/savage/emeraldeconomy/rewards/DailyRewards.java`
- Test: `src/test/java/savage/emeraldeconomy/rewards/DailyRewardsTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/savage/emeraldeconomy/rewards/DailyRewardsTest.java`:

```java
package savage.emeraldeconomy.rewards;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DailyRewardsTest {

    private static final long HOUR = 3600_000L;

    @Test
    void neverClaimedIsImmediatelyClaimable() {
        assertEquals(0L, DailyRewards.remainingCooldownMillis(null, 1_000_000L, 24));
    }

    @Test
    void withinCooldownReturnsRemaining() {
        long now = 10_000_000L;
        long last = now - HOUR; // claimed 1h ago, 24h cooldown -> 23h left
        assertEquals(23L * HOUR, DailyRewards.remainingCooldownMillis(last, now, 24));
    }

    @Test
    void pastCooldownReturnsZero() {
        long now = 100_000_000L;
        long last = now - (25L * HOUR); // 25h ago, > 24h cooldown
        assertEquals(0L, DailyRewards.remainingCooldownMillis(last, now, 24));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat test --tests "savage.emeraldeconomy.rewards.DailyRewardsTest"
```
Expected: FAIL — compilation error, `DailyRewards` / `remainingCooldownMillis` does not exist.

- [ ] **Step 3: Create `DailyRewards`**

Create `src/main/java/savage/emeraldeconomy/rewards/DailyRewards.java`:

```java
package savage.emeraldeconomy.rewards;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import savage.emeraldeconomy.EmeraldEconomy;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks each player's last /daily claim in config/emerald-economy/rewards.json as a flat
 * { "<uuid>": <epochMillis> } map. Survives restarts. Loaded on SERVER_STARTING and written
 * after each successful claim.
 */
public class DailyRewards {

    private static DailyRewards instance;

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<String, Long> lastClaim = new HashMap<>();

    public static DailyRewards getInstance() {
        if (instance == null) {
            instance = new DailyRewards();
        }
        return instance;
    }

    private Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("emerald-economy").resolve("rewards.json");
    }

    /** Load rewards.json into memory. A missing file is treated as "nobody has claimed". */
    public void load() {
        File f = file().toFile();
        if (!f.exists()) {
            return;
        }
        try (FileReader reader = new FileReader(f)) {
            Map<String, Long> data = gson.fromJson(reader, new TypeToken<Map<String, Long>>() {}.getType());
            lastClaim.clear();
            if (data != null) {
                lastClaim.putAll(data);
            }
        } catch (IOException e) {
            EmeraldEconomy.LOGGER.error("Failed to read rewards.json; treating as empty", e);
        }
    }

    private void save() {
        File f = file().toFile();
        f.getParentFile().mkdirs();
        try (FileWriter writer = new FileWriter(f)) {
            gson.toJson(lastClaim, writer);
        } catch (IOException e) {
            EmeraldEconomy.LOGGER.error("Failed to write rewards.json", e);
        }
    }

    /** Millis until {@code uuid} may claim again; 0 if claimable now. */
    public long remainingCooldownMillis(UUID uuid, long nowMillis, int cooldownHours) {
        return remainingCooldownMillis(lastClaim.get(uuid.toString()), nowMillis, cooldownHours);
    }

    /** Pure cooldown math. {@code lastClaimMillis} null means never claimed. */
    public static long remainingCooldownMillis(Long lastClaimMillis, long nowMillis, int cooldownHours) {
        if (lastClaimMillis == null) {
            return 0L;
        }
        long cooldownMillis = (long) cooldownHours * 3600_000L;
        long remaining = cooldownMillis - (nowMillis - lastClaimMillis);
        return Math.max(0L, remaining);
    }

    /** Record a successful claim at {@code nowMillis} and persist. */
    public void markClaimed(UUID uuid, long nowMillis) {
        lastClaim.put(uuid.toString(), nowMillis);
        save();
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat test --tests "savage.emeraldeconomy.rewards.DailyRewardsTest"
```
Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/savage/emeraldeconomy/rewards/DailyRewards.java src/test/java/savage/emeraldeconomy/rewards/DailyRewardsTest.java
git commit -m "feat: add DailyRewards store with rolling-cooldown math

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: `/daily` command + wiring (TDD on duration formatting)

**Files:**
- Create: `src/main/java/savage/emeraldeconomy/command/DailyCommand.java`
- Test: `src/test/java/savage/emeraldeconomy/command/DailyCommandTest.java`
- Modify: `src/main/java/savage/emeraldeconomy/EmeraldEconomy.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/savage/emeraldeconomy/command/DailyCommandTest.java`:

```java
package savage.emeraldeconomy.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DailyCommandTest {

    @Test
    void formatsHoursAndMinutes() {
        long d = (2L * 3600 + 30 * 60) * 1000L; // 2h 30m
        assertEquals("2h 30m", DailyCommand.formatDuration(d));
    }

    @Test
    void formatsMinutesOnlyUnderAnHour() {
        long d = 45L * 60 * 1000L;
        assertEquals("45m", DailyCommand.formatDuration(d));
    }

    @Test
    void formatsSubMinuteAsLessThanOne() {
        assertEquals("<1m", DailyCommand.formatDuration(30_000L));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat test --tests "savage.emeraldeconomy.command.DailyCommandTest"
```
Expected: FAIL — compilation error, `DailyCommand` / `formatDuration` does not exist.

- [ ] **Step 3: Create `DailyCommand`**

Create `src/main/java/savage/emeraldeconomy/command/DailyCommand.java`:

```java
package savage.emeraldeconomy.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import savage.emeraldeconomy.EconomyManager;
import savage.emeraldeconomy.config.EconomyConfig;
import savage.emeraldeconomy.rewards.DailyRewards;

import java.math.BigDecimal;
import java.util.UUID;

/** /daily — claim a once-per-cooldown cash reward. Default-allowed to all players. */
public class DailyCommand {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("daily")
                .requires(source -> savage.emeraldeconomy.util.PermissionsHelper.check(source, "emeraldeconomy.command.daily", true))
                .executes(DailyCommand::claim));
    }

    private static int claim(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        UUID uuid = player.getUuid();
        EconomyConfig.RewardsConfig rewards = EconomyManager.getInstance().getConfig().rewards;

        long now = System.currentTimeMillis();
        long remaining = DailyRewards.getInstance().remainingCooldownMillis(uuid, now, rewards.dailyCooldownHours);
        if (remaining > 0) {
            context.getSource().sendError(Text.literal("Come back in " + formatDuration(remaining) + "."));
            return 0;
        }

        BigDecimal amount = rewards.dailyAmount;
        if (!EconomyManager.getInstance().addBalance(uuid, amount)) {
            context.getSource().sendError(Text.literal("Could not claim daily, please try again."));
            return 0;
        }
        DailyRewards.getInstance().markClaimed(uuid, now);

        String formatted = EconomyManager.getInstance().format(amount);
        context.getSource().sendFeedback(() -> Text.literal("Daily reward claimed: " + formatted + "."), false);
        savage.emeraldeconomy.util.TransactionLogger.log("DAILY", "Server", player.getName().getString(), amount, "Daily reward");
        return 1;
    }

    /** "Xh Ym" with an hour or more, "Ym" under an hour, "<1m" under a minute. */
    static String formatDuration(long millis) {
        long totalMinutes = millis / 60_000L;
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0) {
            return minutes + "m";
        }
        return "<1m";
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat test --tests "savage.emeraldeconomy.command.DailyCommandTest"
```
Expected: PASS.

- [ ] **Step 5: Register `/daily` and load `DailyRewards` in `EmeraldEconomy`**

In `src/main/java/savage/emeraldeconomy/EmeraldEconomy.java`:

Edit A — add the import alongside the other `command` imports (after the `BuyCommand` import, before `DebugCommands`):
```java
import savage.emeraldeconomy.command.DailyCommand;
```

Edit B — add `DailyCommand::register` to the command list. Replace the `List.of(...)` block:
```java
		List<EconomyCommand> commands = List.of(
				BalanceCommands::register,
				DailyCommand::register,
				AdminMoneyCommands::register,
				SellCommands::register,
				BuyCommand::register,
				DepositCommand::register,
				LogCommand::register,
				DebugCommands::register,
				EcoCommands::register,
				ShopCommand::register);
```

Edit C — load `DailyRewards` in the `SERVER_STARTING` handler. Replace that handler:
```java
		// Load economy data when server starts
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			EconomyManager.getInstance().initStorage();
			EconomyManager.getInstance().setServer(server);
			EconomyManager.getInstance().load();
			savage.emeraldeconomy.rewards.DailyRewards.getInstance().load();
		});
```

- [ ] **Step 6: Build to verify it compiles and all tests pass**

Run:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat build
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```powershell
git add src/main/java/savage/emeraldeconomy/command/DailyCommand.java src/test/java/savage/emeraldeconomy/command/DailyCommandTest.java src/main/java/savage/emeraldeconomy/EmeraldEconomy.java
git commit -m "feat: add /daily reward command, register it and load DailyRewards on start

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: Mob-kill rewards

Classification and payout require a running server, so this task is verified by build + smoke test (no unit test).

**Files:**
- Create: `src/main/java/savage/emeraldeconomy/rewards/MobKillRewards.java`
- Modify: `src/main/java/savage/emeraldeconomy/EmeraldEconomy.java`

- [ ] **Step 1: Create `MobKillRewards`**

Create `src/main/java/savage/emeraldeconomy/rewards/MobKillRewards.java`:

```java
package savage.emeraldeconomy.rewards;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import savage.emeraldeconomy.EconomyManager;
import savage.emeraldeconomy.config.EconomyConfig;

import java.math.BigDecimal;

/**
 * Pays a configurable reward to a player who kills a mob. A mob listed in
 * rewards.mobKillRewards pays its override amount (the "tier list"); otherwise hostile
 * mobs (SpawnGroup.MONSTER) pay rewards.mobKillHostile and everything else pays
 * rewards.mobKillPassive. PvP and environmental deaths pay nothing. Feedback is an
 * action-bar overlay so it never spams chat (mob kills are frequent).
 */
public final class MobKillRewards {

    private MobKillRewards() {
    }

    public static void register() {
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            // Killer must be a player (projectiles credit the shooter; null attacker -> false).
            if (!(damageSource.getAttacker() instanceof ServerPlayerEntity killer)) {
                return;
            }
            // Never reward PvP.
            if (entity instanceof PlayerEntity) {
                return;
            }

            EconomyConfig.RewardsConfig rewards = EconomyManager.getInstance().getConfig().rewards;
            String entityId = Registries.ENTITY_TYPE.getId(entity.getType()).toString();

            // Per-mob override (the "tier list") wins; otherwise fall back to the flat rate.
            BigDecimal override = rewards.mobKillRewards != null ? rewards.mobKillRewards.get(entityId) : null;
            BigDecimal reward = override != null
                    ? override
                    : (isHostile(entity) ? rewards.mobKillHostile : rewards.mobKillPassive);
            if (reward == null || reward.signum() <= 0) {
                return;
            }

            if (EconomyManager.getInstance().addBalance(killer.getUuid(), reward)) {
                killer.sendMessage(Text.literal("+" + EconomyManager.getInstance().format(reward)), true);
                savage.emeraldeconomy.util.TransactionLogger.log("MOBKILL", "Server",
                        killer.getName().getString(), reward, entityId);
            }
        });
    }

    /** Hostile = the MONSTER spawn group. */
    static boolean isHostile(LivingEntity entity) {
        return entity.getType().getSpawnGroup() == SpawnGroup.MONSTER;
    }
}
```

- [ ] **Step 2: Register the listener in `EmeraldEconomy`**

In `src/main/java/savage/emeraldeconomy/EmeraldEconomy.java`, add this line at the end of `onInitialize()` (after the existing `ServerPlayConnectionEvents.DISCONNECT` registration, before the method's closing brace):

```java
		// New-player income: reward players for killing mobs.
		savage.emeraldeconomy.rewards.MobKillRewards.register();
```

- [ ] **Step 3: Build to verify it compiles and all tests pass**

Run:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat build
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```powershell
git add src/main/java/savage/emeraldeconomy/rewards/MobKillRewards.java src/main/java/savage/emeraldeconomy/EmeraldEconomy.java
git commit -m "feat: reward players with action-bar cash for mob kills (per-mob overrides)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: Session-based playtime income (TDD on minute math)

**Files:**
- Create: `src/main/java/savage/emeraldeconomy/rewards/PlaytimeIncome.java`
- Test: `src/test/java/savage/emeraldeconomy/rewards/PlaytimeIncomeTest.java`
- Modify: `src/main/java/savage/emeraldeconomy/EmeraldEconomy.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/savage/emeraldeconomy/rewards/PlaytimeIncomeTest.java`:

```java
package savage.emeraldeconomy.rewards;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlaytimeIncomeTest {

    @Test
    void floorsToWholeMinutes() {
        long now = (5L * 60 + 59) * 1000L; // 5m59s -> 5
        assertEquals(5L, PlaytimeIncome.sessionMinutes(0L, now));
    }

    @Test
    void exactMinutesCountExactly() {
        assertEquals(3L, PlaytimeIncome.sessionMinutes(0L, 180_000L));
    }

    @Test
    void negativeIntervalIsZero() {
        assertEquals(0L, PlaytimeIncome.sessionMinutes(100_000L, 0L));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat test --tests "savage.emeraldeconomy.rewards.PlaytimeIncomeTest"
```
Expected: FAIL — compilation error, `PlaytimeIncome` / `sessionMinutes` does not exist.

- [ ] **Step 3: Create `PlaytimeIncome`**

Create `src/main/java/savage/emeraldeconomy/rewards/PlaytimeIncome.java`:

```java
package savage.emeraldeconomy.rewards;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import savage.emeraldeconomy.EconomyManager;
import savage.emeraldeconomy.EmeraldEconomy;
import savage.emeraldeconomy.config.EconomyConfig;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session-based playtime income. Join records a start stamp; disconnect pays
 * floor(minutes) * playtimePerMinute. Counts all online time (AFK included). A graceful
 * shutdown disconnects players first, so the scheduled restart pays out; a hard crash
 * loses the in-progress session.
 *
 * Because payout happens after the player has left, each successful payout is also recorded
 * as a per-player summary persisted in playtime.json and shown as a "welcome back" message
 * on the player's next join (so the message survives the 6h restart between paying and
 * re-login).
 */
public final class PlaytimeIncome {

    private static final Map<UUID, Long> sessionStart = new ConcurrentHashMap<>();
    private static final Map<String, LastSession> lastSession = new ConcurrentHashMap<>();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private PlaytimeIncome() {
    }

    /** Summary of the most recently finished session, pending a welcome-back message. */
    static class LastSession {
        BigDecimal amount;
        long minutes;

        LastSession(BigDecimal amount, long minutes) {
            this.amount = amount;
            this.minutes = minutes;
        }
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("emerald-economy").resolve("playtime.json");
    }

    /** Load pending welcome-back summaries from playtime.json. Missing file = none pending. */
    public static void load() {
        File f = file().toFile();
        if (!f.exists()) {
            return;
        }
        try (FileReader reader = new FileReader(f)) {
            Map<String, LastSession> data = GSON.fromJson(reader, new TypeToken<Map<String, LastSession>>() {}.getType());
            lastSession.clear();
            if (data != null) {
                lastSession.putAll(data);
            }
        } catch (IOException e) {
            EmeraldEconomy.LOGGER.error("Failed to read playtime.json; treating as empty", e);
        }
    }

    private static void save() {
        File f = file().toFile();
        f.getParentFile().mkdirs();
        try (FileWriter writer = new FileWriter(f)) {
            GSON.toJson(lastSession, writer);
        } catch (IOException e) {
            EmeraldEconomy.LOGGER.error("Failed to write playtime.json", e);
        }
    }

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.player;
            // Greet with last session's earnings, if any, then start a fresh session.
            LastSession last = lastSession.remove(player.getUuid().toString());
            if (last != null) {
                save();
                player.sendMessage(Text.literal("Welcome back! Last session you earned "
                        + EconomyManager.getInstance().format(last.amount) + " for " + last.minutes + " min played."), false);
            }
            sessionStart.put(player.getUuid(), System.currentTimeMillis());
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                payout(handler.player, System.currentTimeMillis()));
    }

    private static void payout(ServerPlayerEntity player, long nowMillis) {
        Long start = sessionStart.remove(player.getUuid());
        if (start == null) {
            return;
        }

        EconomyConfig.RewardsConfig rewards = EconomyManager.getInstance().getConfig().rewards;
        long minutes = sessionMinutes(start, nowMillis);
        if (minutes <= 0 || rewards.playtimePerMinute == null || rewards.playtimePerMinute.signum() <= 0) {
            return;
        }

        BigDecimal amount = rewards.playtimePerMinute.multiply(BigDecimal.valueOf(minutes));
        if (EconomyManager.getInstance().addBalance(player.getUuid(), amount)) {
            lastSession.put(player.getUuid().toString(), new LastSession(amount, minutes));
            save();
            EmeraldEconomy.LOGGER.info("Paid {} playtime income to {} for {} min played",
                    EconomyManager.getInstance().format(amount), player.getName().getString(), minutes);
            savage.emeraldeconomy.util.TransactionLogger.log("PLAYTIME", "Server",
                    player.getName().getString(), amount, minutes + " min played");
        }
    }

    /** Whole minutes between two epoch-millis stamps, floored and never negative. */
    public static long sessionMinutes(long startMillis, long nowMillis) {
        return Math.max(0L, (nowMillis - startMillis) / 60_000L);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat test --tests "savage.emeraldeconomy.rewards.PlaytimeIncomeTest"
```
Expected: PASS.

- [ ] **Step 5: Register the listeners and load the summary store in `EmeraldEconomy`**

In `src/main/java/savage/emeraldeconomy/EmeraldEconomy.java`:

Edit A — load the welcome-back summaries on start. Replace the `SERVER_STARTING` handler (it gained the `DailyRewards` load in Task 3) so it also loads `PlaytimeIncome`:
```java
		// Load economy data when server starts
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			EconomyManager.getInstance().initStorage();
			EconomyManager.getInstance().setServer(server);
			EconomyManager.getInstance().load();
			savage.emeraldeconomy.rewards.DailyRewards.getInstance().load();
			savage.emeraldeconomy.rewards.PlaytimeIncome.load();
		});
```

Edit B — register the listeners. Add this line at the end of `onInitialize()` (directly after the `MobKillRewards.register();` line added in Task 4):
```java
		// New-player income: session-based playtime payout, shown as a welcome-back message.
		savage.emeraldeconomy.rewards.PlaytimeIncome.register();
```

- [ ] **Step 6: Build to verify it compiles and all tests pass**

Run:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat build
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```powershell
git add src/main/java/savage/emeraldeconomy/rewards/PlaytimeIncome.java src/test/java/savage/emeraldeconomy/rewards/PlaytimeIncomeTest.java src/main/java/savage/emeraldeconomy/EmeraldEconomy.java
git commit -m "feat: pay session-based playtime income on disconnect

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Final verification

- [ ] **Full build green (compiles + all unit tests):**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat build
```
Expected: `BUILD SUCCESSFUL`, with `DailyRewardsTest`, `DailyCommandTest`, `PlaytimeIncomeTest`, and `PriceBookTest` all green.

- [ ] **In-game smoke test (run the server with the built jar):**
  - `/daily` pays `dailyAmount` once, then reports `"Come back in Xh Ym."` on a second try.
  - Edit `config/emerald-economy/config.json` `rewards.dailyAmount`, run `/eco reload`, `/daily` (after cooldown, or set a low cooldown) reflects the new value — confirms live reload of `config.json`.
  - Restart the server; `/daily` is still on cooldown — confirms `rewards.json` persistence.
  - Kill a zombie → action-bar `+$100` and balance rises; kill a cow → `+$50` (passive is on by default now). Set `mobKillPassive` to `0`, `/eco reload`, kill a cow → nothing — confirms the off path too.
  - Kill a blaze → `+$250` (the `mobKillRewards` override beats the flat hostile rate). Add another entity id to `mobKillRewards`, `/eco reload`, kill it → its override amount — confirms the tier map is live-tunable.
  - Hit another player to death (or `/kill` a player) → no MOBKILL payout.
  - Stay online a few minutes, log out, rejoin → balance increased by `minutes × playtimePerMinute`, a `Paid $X playtime income…` line is in the server log, and on rejoin you see `"Welcome back! Last session you earned $X for N min played."`.
  - Repeat but **restart the server** between the logout and the rejoin → the welcome-back message still appears (confirms `playtime.json` persistence).
  - `/log <name>` (or the economy log) shows `DAILY`, `MOBKILL`, and `PLAYTIME` entries.

- [ ] **New files exist and are committed:**
  - `src/main/java/savage/emeraldeconomy/rewards/DailyRewards.java`
  - `src/main/java/savage/emeraldeconomy/rewards/MobKillRewards.java`
  - `src/main/java/savage/emeraldeconomy/rewards/PlaytimeIncome.java`
  - `src/main/java/savage/emeraldeconomy/command/DailyCommand.java`
