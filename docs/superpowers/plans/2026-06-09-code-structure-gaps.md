# Code Structure Gaps Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close three structural gaps in the SQLite-only emerald economy: a 320-line command god-class, a dead multi-dialect storage abstraction, and silent `printStackTrace` error handling.

**Architecture:** Split `EconomyCommands` into player (`BalanceCommands`) and admin (`AdminMoneyCommands`) groups with a shared helper and a one-line registration loop; collapse `SqlStorage`+`SqliteStorage` into one concrete `SqliteStorage`; replace `printStackTrace` with `LOGGER.error` and make balance/account/mutating storage paths throw `EconomyStorageException` instead of returning silent defaults.

**Tech Stack:** Java 21, Fabric Loom, Brigadier commands, savdbcore SQLite storage, SLF4J logging. Spec: `docs/superpowers/specs/2026-06-09-code-structure-gaps-design.md`.

---

## Verification harness (read first)

This is a **refactor**, not new feature logic. The one genuinely new runtime behavior (storage throwing on DB failure) cannot be unit-tested deterministically without a fault-injecting DB harness, which would be brittle. So the verification harness for every task is:

1. The project compiles, and
2. The existing `economy/PriceBookTest` still passes.

Both are covered by a single command. **`JAVA_HOME` must be set first** — Java is not on PATH.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat build
```

Expected on success: `BUILD SUCCESSFUL` (and the `:test` task runs `PriceBookTest` green).

Every commit message ends with the co-author trailer:

```
Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
```

---

## File structure

**Create:**
- `src/main/java/savage/commoneconomy/storage/EconomyStorageException.java` — unchecked exception for unrecoverable storage faults.
- `src/main/java/savage/commoneconomy/command/EconomyCommand.java` — functional interface for a registrable command group.
- `src/main/java/savage/commoneconomy/command/CommandSupport.java` — shared helpers (player suggestions, target resolution, feedback).
- `src/main/java/savage/commoneconomy/command/BalanceCommands.java` — player commands `/bal`, `/baltop`, `/pay`, `/withdraw`.
- `src/main/java/savage/commoneconomy/command/AdminMoneyCommands.java` — admin commands `/givemoney`, `/takemoney`, `/setmoney`, `/resetmoney`.

**Modify:**
- `src/main/java/savage/commoneconomy/storage/SqliteStorage.java` — absorb `SqlStorage`, add fail-loud error handling.
- `src/main/java/savage/commoneconomy/util/TransactionLogger.java` — swap `instanceof SqlStorage` → `instanceof SqliteStorage`.
- `src/main/java/savage/commoneconomy/EconomyManager.java` — `printStackTrace` → `LOGGER.error`.
- `src/main/java/savage/commoneconomy/SavsCommonEconomy.java` — registration loop + imports.

**Delete:**
- `src/main/java/savage/commoneconomy/storage/SqlStorage.java`
- `src/main/java/savage/commoneconomy/command/EconomyCommands.java`

Task order: storage (Tasks 1–2) → manager logging (Task 3) → commands (Task 4). Each task ends green.

---

### Task 1: Add `EconomyStorageException`

**Files:**
- Create: `src/main/java/savage/commoneconomy/storage/EconomyStorageException.java`

- [ ] **Step 1: Create the exception class**

```java
package savage.commoneconomy.storage;

/**
 * Thrown when an economy storage operation fails in a way that must not be
 * silently swallowed (a balance/account read or a state-mutating write).
 * Surfacing this as an unchecked exception lets a database fault propagate to
 * the command layer instead of being read as $0 or an empty result.
 */
public class EconomyStorageException extends RuntimeException {
    public EconomyStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 2: Build to verify it compiles**

Run:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat build
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```powershell
git add src/main/java/savage/commoneconomy/storage/EconomyStorageException.java
git commit -m "feat: add EconomyStorageException for fail-loud storage errors

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: Collapse storage layers + fail-loud error handling

Merge the abstract `SqlStorage` into the concrete `SqliteStorage`, delete `SqlStorage.java`, update `TransactionLogger`, and make balance/account/mutating paths throw on `SQLException`.

**Files:**
- Modify: `src/main/java/savage/commoneconomy/storage/SqliteStorage.java`
- Delete: `src/main/java/savage/commoneconomy/storage/SqlStorage.java`
- Modify: `src/main/java/savage/commoneconomy/util/TransactionLogger.java`

- [ ] **Step 1: Replace the entire contents of `SqliteStorage.java`**

Overwrite the file with this complete implementation. Read methods (`getBalance`, `getAccount`, `hasAccount`, `getUUID`) and mutating methods (`setBalance` ×2, `createAccount`, `deleteAccount`, `logTransaction`, `createTables`) log and throw on `SQLException`; read-only listing methods (`getOfflinePlayerNames`, `getTopAccounts`, `searchLogs`) log and return an empty result.

```java
package savage.commoneconomy.storage;

import savage.commoneconomy.EconomyManager.AccountData;
import savage.commoneconomy.SavsCommonEconomy;
import savage.savdbcore.config.DBCoreConfig;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * SQLite storage for economy data. Implements {@link EconomyStorage} and
 * delegates connection management to savdbcore's SqlStorage. This is the single
 * concrete storage backend (the mod is SQLite-only). Balance/account reads and
 * state-mutating writes throw {@link EconomyStorageException} on failure rather
 * than returning a silent default; read-only listing helpers log and return
 * empty.
 */
public class SqliteStorage extends savage.savdbcore.storage.SqlStorage implements EconomyStorage {
    private final savage.commoneconomy.EconomyManager manager;

    public SqliteStorage(savage.commoneconomy.EconomyManager manager, String tablePrefix) {
        super(tablePrefix);
        this.manager = manager;

        // Convert economy config to DBCore config.
        DBCoreConfig.StorageConfig coreConfig = new DBCoreConfig.StorageConfig();
        coreConfig.poolSize = manager.getConfig().storage.poolSize;
        coreConfig.connectionTimeout = manager.getConfig().storage.connectionTimeout;
        coreConfig.idleTimeout = manager.getConfig().storage.idleTimeout;

        savage.savdbcore.storage.SqliteStorage dbStorage = new savage.savdbcore.storage.SqliteStorage(
                "savs-common-economy",
                "economy_data.sqlite",
                tablePrefix,
                coreConfig);
        dbStorage.initialize();

        this.dataSource = dbStorage.dataSource;
    }

    @Override
    protected void setupDataSource() {
        // The data source is already set up in the constructor.
    }

    private String getTransactionsTableCreationSql() {
        return "CREATE TABLE IF NOT EXISTS " + tablePrefix + "transactions (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "timestamp BIGINT NOT NULL, " +
                "source VARCHAR(16) NOT NULL, " +
                "target VARCHAR(16) NOT NULL, " +
                "amount DECIMAL(20, 2) NOT NULL, " +
                "type VARCHAR(16) NOT NULL, " +
                "details VARCHAR(255)" +
                ")";
    }

    private void createTables() {
        try (Connection conn = getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS " + tablePrefix + "accounts (" +
                            "uuid VARCHAR(36) PRIMARY KEY, " +
                            "name VARCHAR(16) NOT NULL, " +
                            "balance DECIMAL(20, 2) NOT NULL, " +
                            "version BIGINT DEFAULT 0" +
                            ")")) {
                stmt.executeUpdate();
            }

            // Migration: add the version column to pre-existing tables that lack it.
            try (PreparedStatement checkStmt = conn.prepareStatement("SELECT version FROM " + tablePrefix + "accounts LIMIT 1")) {
                checkStmt.executeQuery();
            } catch (SQLException e) {
                try (PreparedStatement alterStmt = conn.prepareStatement("ALTER TABLE " + tablePrefix + "accounts ADD COLUMN version BIGINT DEFAULT 0")) {
                    alterStmt.executeUpdate();
                } catch (SQLException ex) {
                    SavsCommonEconomy.LOGGER.error("Failed to add 'version' column during migration", ex);
                }
            }

            try (PreparedStatement stmt = conn.prepareStatement(getTransactionsTableCreationSql())) {
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            SavsCommonEconomy.LOGGER.error("Failed to create economy tables", e);
            throw new EconomyStorageException("Failed to create economy tables", e);
        }
    }

    @Override
    public void load() {
        initialize();
        createTables();
    }

    @Override
    public void save() {
        shutdown();
    }

    @Override
    public BigDecimal getBalance(UUID uuid) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT balance FROM " + tablePrefix + "accounts WHERE uuid = ?")) {
            stmt.setString(1, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getBigDecimal("balance");
                }
            }
        } catch (SQLException e) {
            SavsCommonEconomy.LOGGER.error("Failed to read balance for {}", uuid, e);
            throw new EconomyStorageException("Failed to read balance for " + uuid, e);
        }
        return BigDecimal.ZERO;
    }

    @Override
    public void setBalance(UUID uuid, BigDecimal amount) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE " + tablePrefix + "accounts SET balance = ?, version = version + 1 WHERE uuid = ?")) {
            stmt.setBigDecimal(1, amount);
            stmt.setString(2, uuid.toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            SavsCommonEconomy.LOGGER.error("Failed to set balance for {}", uuid, e);
            throw new EconomyStorageException("Failed to set balance for " + uuid, e);
        }
    }

    @Override
    public boolean setBalance(UUID uuid, BigDecimal amount, long expectedVersion) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE " + tablePrefix + "accounts SET balance = ?, version = version + 1 WHERE uuid = ? AND version = ?")) {
            stmt.setBigDecimal(1, amount);
            stmt.setString(2, uuid.toString());
            stmt.setLong(3, expectedVersion);
            int rows = stmt.executeUpdate();
            return rows > 0;
        } catch (SQLException e) {
            SavsCommonEconomy.LOGGER.error("Failed to set balance (versioned) for {}", uuid, e);
            throw new EconomyStorageException("Failed to set balance for " + uuid, e);
        }
    }

    @Override
    public boolean hasAccount(UUID uuid) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT 1 FROM " + tablePrefix + "accounts WHERE uuid = ?")) {
            stmt.setString(1, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            SavsCommonEconomy.LOGGER.error("Failed to check account for {}", uuid, e);
            throw new EconomyStorageException("Failed to check account for " + uuid, e);
        }
    }

    @Override
    public AccountData getAccount(UUID uuid) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT name, balance, version FROM " + tablePrefix + "accounts WHERE uuid = ?")) {
            stmt.setString(1, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new AccountData(
                            rs.getString("name"),
                            rs.getBigDecimal("balance"),
                            rs.getLong("version"));
                }
            }
        } catch (SQLException e) {
            SavsCommonEconomy.LOGGER.error("Failed to read account for {}", uuid, e);
            throw new EconomyStorageException("Failed to read account for " + uuid, e);
        }
        return null;
    }

    @Override
    public void createAccount(UUID uuid, String name) {
        if (hasAccount(uuid)) {
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "UPDATE " + tablePrefix + "accounts SET name = ? WHERE uuid = ?")) {
                stmt.setString(1, name);
                stmt.setString(2, uuid.toString());
                stmt.executeUpdate();
            } catch (SQLException e) {
                SavsCommonEconomy.LOGGER.error("Failed to update account name for {}", uuid, e);
                throw new EconomyStorageException("Failed to update account name for " + uuid, e);
            }
        } else {
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "INSERT INTO " + tablePrefix + "accounts (uuid, name, balance, version) VALUES (?, ?, ?, 0)")) {
                stmt.setString(1, uuid.toString());
                stmt.setString(2, name);
                stmt.setBigDecimal(3, manager.getConfig().defaultBalance);
                stmt.executeUpdate();
            } catch (SQLException e) {
                SavsCommonEconomy.LOGGER.error("Failed to create account for {}", uuid, e);
                throw new EconomyStorageException("Failed to create account for " + uuid, e);
            }
        }
    }

    @Override
    public UUID getUUID(String name) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT uuid FROM " + tablePrefix + "accounts WHERE LOWER(name) = LOWER(?)")) {
            stmt.setString(1, name);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return UUID.fromString(rs.getString("uuid"));
                }
            }
        } catch (SQLException e) {
            SavsCommonEconomy.LOGGER.error("Failed to look up UUID for {}", name, e);
            throw new EconomyStorageException("Failed to look up UUID for " + name, e);
        }
        return null;
    }

    @Override
    public Collection<String> getOfflinePlayerNames() {
        List<String> names = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT name FROM " + tablePrefix + "accounts");
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                names.add(rs.getString("name"));
            }
        } catch (SQLException e) {
            SavsCommonEconomy.LOGGER.error("Failed to list offline player names", e);
        }
        return names;
    }

    @Override
    public void logTransaction(long timestamp, String source, String target, BigDecimal amount, String type, String details) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO " + tablePrefix + "transactions (timestamp, source, target, amount, type, details) VALUES (?, ?, ?, ?, ?, ?)")) {
            stmt.setLong(1, timestamp);
            stmt.setString(2, source);
            stmt.setString(3, target);
            stmt.setBigDecimal(4, amount);
            stmt.setString(5, type);
            stmt.setString(6, details);
            stmt.executeUpdate();
        } catch (SQLException e) {
            SavsCommonEconomy.LOGGER.error("Failed to log transaction ({} -> {})", source, target, e);
            throw new EconomyStorageException("Failed to log transaction", e);
        }
    }

    @Override
    public List<savage.commoneconomy.util.TransactionLogger.LogEntry> searchLogs(String target, long cutoffTimestamp) {
        List<savage.commoneconomy.util.TransactionLogger.LogEntry> logs = new ArrayList<>();
        String sql = "SELECT timestamp, source, target, amount, type, details FROM " + tablePrefix + "transactions WHERE timestamp > ?";
        if (!target.equals("*")) {
            sql += " AND (LOWER(source) LIKE ? OR LOWER(target) LIKE ?)";
        }
        sql += " ORDER BY timestamp DESC";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, cutoffTimestamp);
            if (!target.equals("*")) {
                String search = "%" + target.toLowerCase() + "%";
                stmt.setString(2, search);
                stmt.setString(3, search);
            }

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    logs.add(new savage.commoneconomy.util.TransactionLogger.LogEntry(
                            java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(rs.getLong("timestamp")), java.time.ZoneId.systemDefault()),
                            rs.getString("type"),
                            rs.getString("source"),
                            rs.getString("target"),
                            rs.getBigDecimal("amount"),
                            rs.getString("details")));
                }
            }
        } catch (SQLException e) {
            SavsCommonEconomy.LOGGER.error("Failed to search transaction logs", e);
        }
        return logs;
    }

    @Override
    public List<AccountData> getTopAccounts(int limit) {
        List<AccountData> accounts = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT name, balance, version FROM " + tablePrefix + "accounts ORDER BY balance DESC LIMIT ?")) {
            stmt.setInt(1, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    accounts.add(new AccountData(
                            rs.getString("name"),
                            rs.getBigDecimal("balance"),
                            rs.getLong("version")));
                }
            }
        } catch (SQLException e) {
            SavsCommonEconomy.LOGGER.error("Failed to read top accounts", e);
        }
        return accounts;
    }

    @Override
    public void deleteAccount(UUID uuid) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement("DELETE FROM " + tablePrefix + "accounts WHERE uuid = ?")) {
            stmt.setString(1, uuid.toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            SavsCommonEconomy.LOGGER.error("Failed to delete account for {}", uuid, e);
            throw new EconomyStorageException("Failed to delete account for " + uuid, e);
        }
    }
}
```

- [ ] **Step 2: Delete the now-empty abstract base**

```powershell
git rm src/main/java/savage/commoneconomy/storage/SqlStorage.java
```

- [ ] **Step 3: Update `TransactionLogger` to reference `SqliteStorage`**

In `src/main/java/savage/commoneconomy/util/TransactionLogger.java`, there are two `instanceof savage.commoneconomy.storage.SqlStorage` checks. Replace both with `instanceof savage.commoneconomy.storage.SqliteStorage`.

Edit 1 (in `log`, ~line 27):
```java
            if (storage instanceof savage.commoneconomy.storage.SqliteStorage) {
                storage.logTransaction(timestamp, source, target, amount, type, details);
                return;
            }
```

Edit 2 (in `searchLogs`, ~line 54):
```java
        if (storage instanceof savage.commoneconomy.storage.SqliteStorage) {
            long cutoffTimestamp = cutoff.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            return storage.searchLogs(target, cutoffTimestamp);
        }
```

- [ ] **Step 4: Confirm no remaining references to `SqlStorage`**

Run:
```powershell
Select-String -Path src\main\java\savage\commoneconomy\*.java,src\main\java\savage\commoneconomy\**\*.java -Pattern 'storage\.SqlStorage|class SqlStorage|new SqlStorage'
```
Expected: no matches (only `savage.savdbcore.storage.SqlStorage`, the superclass, may appear inside `SqliteStorage.java` — that is correct and intended).

- [ ] **Step 5: Build to verify it compiles and tests pass**

Run:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat build
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/savage/commoneconomy/storage/SqliteStorage.java src/main/java/savage/commoneconomy/util/TransactionLogger.java
git rm src/main/java/savage/commoneconomy/storage/SqlStorage.java
git commit -m "refactor: collapse storage to one SQLite class, fail loud on DB errors

Merge abstract SqlStorage into SqliteStorage (SQLite-only mod), throw
EconomyStorageException on balance/account/mutating failures instead of
swallowing with printStackTrace and returning defaults.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: Replace `printStackTrace` in `EconomyManager`

`EconomyManager` is in package `savage.commoneconomy`, the same package as `SavsCommonEconomy`, so `SavsCommonEconomy.LOGGER` resolves with no import.

**Files:**
- Modify: `src/main/java/savage/commoneconomy/EconomyManager.java`

- [ ] **Step 1: Replace the five `printStackTrace` calls**

Make these five edits (each `catch` block currently calls `e.printStackTrace();`).

Edit 1 — `loadConfig`, default-config write block:
```java
            try (FileWriter writer = new FileWriter(configFile)) {
                gson.toJson(this.config, writer);
            } catch (IOException e) {
                SavsCommonEconomy.LOGGER.error("Failed to write default economy config", e);
            }
```

Edit 2 — `loadConfig`, read block:
```java
            try (FileReader reader = new FileReader(configFile)) {
                this.config = gson.fromJson(reader, EconomyConfig.class);
            } catch (IOException e) {
                SavsCommonEconomy.LOGGER.error("Failed to read economy config; using defaults", e);
                this.config = new EconomyConfig();
            }
```

Edit 3 — `loadWorthConfig`, read block:
```java
            try (FileReader reader = new FileReader(worthFile)) {
                this.worthConfig = gson.fromJson(reader, WorthConfig.class);
            } catch (IOException e) {
                SavsCommonEconomy.LOGGER.error("Failed to read worth.json; using empty config", e);
                this.worthConfig = new WorthConfig();
            }
```

Edit 4 — `saveWorthConfig`, backup-copy block:
```java
            if (worthFile.exists()) {
                java.nio.file.Files.copy(worthFile.toPath(), backup.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            SavsCommonEconomy.LOGGER.error("Failed to back up worth.json", e);
        }
```

Edit 5 — `saveWorthConfig`, write block:
```java
        try (FileWriter writer = new FileWriter(worthFile)) {
            gson.toJson(this.worthConfig, writer);
        } catch (IOException e) {
            SavsCommonEconomy.LOGGER.error("Failed to write worth.json", e);
        }
```

- [ ] **Step 2: Confirm no `printStackTrace` remains in `EconomyManager` or `storage/`**

Run:
```powershell
Select-String -Path src\main\java\savage\commoneconomy\EconomyManager.java,src\main\java\savage\commoneconomy\storage\*.java -Pattern 'printStackTrace'
```
Expected: no matches.

- [ ] **Step 3: Build to verify it compiles and tests pass**

Run:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat build
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```powershell
git add src/main/java/savage/commoneconomy/EconomyManager.java
git commit -m "refactor: log config/worth IO failures via LOGGER, not printStackTrace

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: Split the command god-class

Introduce `EconomyCommand` (registration interface) and `CommandSupport` (shared helpers), split `EconomyCommands` into `BalanceCommands` (player) and `AdminMoneyCommands` (admin), switch the initializer to a registration loop, and delete `EconomyCommands`. All new command files use 4-space indentation (matching the existing command package); `SavsCommonEconomy.java` uses tabs — preserve them.

**Files:**
- Create: `src/main/java/savage/commoneconomy/command/EconomyCommand.java`
- Create: `src/main/java/savage/commoneconomy/command/CommandSupport.java`
- Create: `src/main/java/savage/commoneconomy/command/BalanceCommands.java`
- Create: `src/main/java/savage/commoneconomy/command/AdminMoneyCommands.java`
- Modify: `src/main/java/savage/commoneconomy/SavsCommonEconomy.java`
- Delete: `src/main/java/savage/commoneconomy/command/EconomyCommands.java`

- [ ] **Step 1: Create the `EconomyCommand` interface**

```java
package savage.commoneconomy.command;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.server.command.ServerCommandSource;

/** A registrable group of economy commands. */
@FunctionalInterface
public interface EconomyCommand {
    void register(CommandDispatcher<ServerCommandSource> dispatcher);
}
```

- [ ] **Step 2: Create `CommandSupport` with the shared helpers**

```java
package savage.commoneconomy.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import savage.commoneconomy.EconomyManager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Shared helpers used by the balance and admin-money command groups. */
final class CommandSupport {

    private CommandSupport() {
    }

    /** Suggests online players plus known offline account names. */
    static final SuggestionProvider<ServerCommandSource> PLAYER_SUGGESTION_PROVIDER = (context, builder) -> {
        List<String> suggestions = new ArrayList<>();
        suggestions.addAll(context.getSource().getPlayerNames());
        suggestions.addAll(EconomyManager.getInstance().getOfflinePlayerNames());
        return CommandSource.suggestMatching(suggestions, builder);
    };

    /** Resolves a target ("@s", an online player, or an offline name) to a UUID, or null if unknown. */
    static UUID getTargetUUID(CommandContext<ServerCommandSource> context, String targetName) throws CommandSyntaxException {
        if (targetName.equals("@s")) {
            return context.getSource().getPlayerOrThrow().getUuid();
        }
        ServerPlayerEntity target = context.getSource().getServer().getPlayerManager().getPlayer(targetName);
        if (target != null) {
            return target.getUuid();
        }
        return EconomyManager.getInstance().getUUID(targetName);
    }

    /** Resolves the display name for a target argument. */
    static String getTargetName(CommandContext<ServerCommandSource> context, String targetName) throws CommandSyntaxException {
        if (targetName.equals("@s")) {
            return context.getSource().getPlayerOrThrow().getName().getString();
        }
        ServerPlayerEntity target = context.getSource().getServer().getPlayerManager().getPlayer(targetName);
        if (target != null) {
            return target.getName().getString();
        }
        return targetName;
    }

    static void sendCommandFeedback(CommandContext<ServerCommandSource> context, String message, boolean broadcastToOps) {
        context.getSource().sendFeedback(() -> Text.literal(message), broadcastToOps);
    }
}
```

- [ ] **Step 3: Create `BalanceCommands` (player commands)**

```java
package savage.commoneconomy.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import savage.commoneconomy.EconomyManager;

import java.math.BigDecimal;
import java.util.UUID;

/** Player-facing balance commands: /bal, /baltop, /pay, /withdraw. */
public class BalanceCommands {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("bal")
                .requires(source -> savage.commoneconomy.util.PermissionsHelper.check(source, "savscommoneconomy.command.bal", true))
                .executes(BalanceCommands::checkSelfBalance)
                .then(CommandManager.argument("target", StringArgumentType.string())
                        .requires(source -> savage.commoneconomy.util.PermissionsHelper.check(source, "savscommoneconomy.command.bal.others", true))
                        .suggests(CommandSupport.PLAYER_SUGGESTION_PROVIDER)
                        .executes(BalanceCommands::checkOtherBalance)));

        dispatcher.register(CommandManager.literal("baltop")
                .requires(source -> savage.commoneconomy.util.PermissionsHelper.check(source, "savscommoneconomy.command.baltop", true))
                .executes(BalanceCommands::balTop));

        dispatcher.register(CommandManager.literal("pay")
                .requires(source -> savage.commoneconomy.util.PermissionsHelper.check(source, "savscommoneconomy.command.pay", true))
                .then(CommandManager.argument("target", StringArgumentType.string())
                        .suggests(CommandSupport.PLAYER_SUGGESTION_PROVIDER)
                        .then(CommandManager.argument("amount", DoubleArgumentType.doubleArg(0))
                                .executes(BalanceCommands::pay))));

        dispatcher.register(CommandManager.literal("withdraw")
                .requires(source -> savage.commoneconomy.util.PermissionsHelper.check(source, "savscommoneconomy.command.withdraw", true))
                .then(CommandManager.argument("amount", DoubleArgumentType.doubleArg(1))
                        .executes(BalanceCommands::withdraw)));
    }

    private static int balTop(CommandContext<ServerCommandSource> context) {
        java.util.List<EconomyManager.AccountData> topAccounts = EconomyManager.getInstance().getTopAccounts(10);

        context.getSource().sendFeedback(() -> Text.literal("--- Balance Top 10 ---"), false);
        for (int i = 0; i < topAccounts.size(); i++) {
            EconomyManager.AccountData account = topAccounts.get(i);
            int rank = i + 1;
            context.getSource().sendFeedback(() -> Text.literal(rank + ". " + account.name + ": " + EconomyManager.getInstance().format(account.balance)), false);
        }
        return 1;
    }

    private static int checkSelfBalance(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        BigDecimal balance = EconomyManager.getInstance().getBalance(player.getUuid());
        context.getSource().sendFeedback(() -> Text.literal("Your balance: " + EconomyManager.getInstance().format(balance)), false);
        return 1;
    }

    private static int checkOtherBalance(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        String targetName = StringArgumentType.getString(context, "target");
        UUID targetUUID = CommandSupport.getTargetUUID(context, targetName);
        String displayName = CommandSupport.getTargetName(context, targetName);

        if (targetUUID == null) {
            context.getSource().sendError(Text.literal("Player not found or has never joined."));
            return 0;
        }

        BigDecimal balance = EconomyManager.getInstance().getBalance(targetUUID);
        context.getSource().sendFeedback(() -> Text.literal(displayName + "'s balance: " + EconomyManager.getInstance().format(balance)), false);
        return 1;
    }

    private static int pay(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity sourcePlayer = context.getSource().getPlayerOrThrow();
        String targetName = StringArgumentType.getString(context, "target");
        double amountDouble = DoubleArgumentType.getDouble(context, "amount");
        BigDecimal amount = BigDecimal.valueOf(amountDouble);

        UUID targetUUID = CommandSupport.getTargetUUID(context, targetName);
        String displayName = CommandSupport.getTargetName(context, targetName);

        if (targetUUID == null) {
            context.getSource().sendError(Text.literal("Player not found or has never joined."));
            return 0;
        }

        if (sourcePlayer.getUuid().equals(targetUUID)) {
            context.getSource().sendError(Text.literal("You cannot pay yourself."));
            return 0;
        }

        if (EconomyManager.getInstance().removeBalance(sourcePlayer.getUuid(), amount)) {
            EconomyManager.getInstance().addBalance(targetUUID, amount);
            String formattedAmount = EconomyManager.getInstance().format(amount);
            CommandSupport.sendCommandFeedback(context, "Paid " + formattedAmount + " to " + displayName, false);

            ServerPlayerEntity target = context.getSource().getServer().getPlayerManager().getPlayer(targetUUID);
            if (target != null) {
                target.sendMessage(Text.literal("Received " + formattedAmount + " from " + sourcePlayer.getName().getString()), false);
            }
            savage.commoneconomy.util.TransactionLogger.log("PAY", sourcePlayer.getName().getString(), displayName, amount, "Payment");
            return 1;
        } else {
            context.getSource().sendError(Text.literal("Insufficient funds."));
            return 0;
        }
    }

    private static int withdraw(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        double amountDouble = DoubleArgumentType.getDouble(context, "amount");
        BigDecimal amount = BigDecimal.valueOf(amountDouble);

        // 1 emerald = $1. Withdraw whole emeralds only.
        BigDecimal whole = amount.setScale(0, java.math.RoundingMode.DOWN);
        if (whole.compareTo(BigDecimal.ONE) < 0) {
            context.getSource().sendError(Text.literal("Withdraw at least 1."));
            return 0;
        }
        if (whole.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
            context.getSource().sendError(Text.literal("That's too many emeralds to withdraw at once (max " + Integer.MAX_VALUE + ")."));
            return 0;
        }
        int emeralds = whole.intValueExact();
        BigDecimal cost = BigDecimal.valueOf(emeralds);

        if (EconomyManager.getInstance().removeBalance(player.getUuid(), cost)) {
            int remaining = emeralds;
            int maxStack = new net.minecraft.item.ItemStack(net.minecraft.item.Items.EMERALD).getMaxCount();
            while (remaining > 0) {
                int give = Math.min(remaining, maxStack);
                player.getInventory().offerOrDrop(new net.minecraft.item.ItemStack(net.minecraft.item.Items.EMERALD, give));
                remaining -= give;
            }
            CommandSupport.sendCommandFeedback(context, "Withdrew " + EconomyManager.getInstance().format(cost) + " as " + emeralds + " emeralds.", false);
            savage.commoneconomy.util.TransactionLogger.log("WITHDRAW", player.getName().getString(), "Emeralds", cost, "Withdrawal");
            return 1;
        } else {
            context.getSource().sendError(Text.literal("Insufficient funds."));
            return 0;
        }
    }
}
```

- [ ] **Step 4: Create `AdminMoneyCommands` (admin commands)**

```java
package savage.commoneconomy.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import savage.commoneconomy.EconomyManager;

import java.math.BigDecimal;
import java.util.UUID;

/** Admin balance-management commands: /givemoney, /takemoney, /setmoney, /resetmoney. */
public class AdminMoneyCommands {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("givemoney")
                .requires(source -> savage.commoneconomy.util.PermissionsHelper.check(source, "savscommoneconomy.admin", 2))
                .then(CommandManager.argument("target", StringArgumentType.string())
                        .suggests(CommandSupport.PLAYER_SUGGESTION_PROVIDER)
                        .then(CommandManager.argument("amount", DoubleArgumentType.doubleArg(0))
                                .executes(AdminMoneyCommands::giveMoney))));

        dispatcher.register(CommandManager.literal("takemoney")
                .requires(source -> savage.commoneconomy.util.PermissionsHelper.check(source, "savscommoneconomy.admin", 2))
                .then(CommandManager.argument("target", StringArgumentType.string())
                        .suggests(CommandSupport.PLAYER_SUGGESTION_PROVIDER)
                        .then(CommandManager.argument("amount", DoubleArgumentType.doubleArg(0))
                                .executes(AdminMoneyCommands::takeMoney))));

        dispatcher.register(CommandManager.literal("setmoney")
                .requires(source -> savage.commoneconomy.util.PermissionsHelper.check(source, "savscommoneconomy.admin", 2))
                .then(CommandManager.argument("target", StringArgumentType.string())
                        .suggests(CommandSupport.PLAYER_SUGGESTION_PROVIDER)
                        .then(CommandManager.argument("amount", DoubleArgumentType.doubleArg(0))
                                .executes(AdminMoneyCommands::setMoney))));

        dispatcher.register(CommandManager.literal("resetmoney")
                .requires(source -> savage.commoneconomy.util.PermissionsHelper.check(source, "savscommoneconomy.admin", 2))
                .then(CommandManager.argument("target", StringArgumentType.string())
                        .suggests(CommandSupport.PLAYER_SUGGESTION_PROVIDER)
                        .executes(AdminMoneyCommands::resetMoney)));
    }

    private static int giveMoney(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        String targetName = StringArgumentType.getString(context, "target");
        double amountDouble = DoubleArgumentType.getDouble(context, "amount");
        BigDecimal amount = BigDecimal.valueOf(amountDouble);
        String formattedAmount = EconomyManager.getInstance().format(amount);

        UUID targetUUID = CommandSupport.getTargetUUID(context, targetName);
        String displayName = CommandSupport.getTargetName(context, targetName);

        if (targetUUID == null) {
            context.getSource().sendError(Text.literal("Player not found or has never joined."));
            return 0;
        }

        if (EconomyManager.getInstance().addBalance(targetUUID, amount)) {
            CommandSupport.sendCommandFeedback(context, "Gave " + formattedAmount + " to " + displayName, true);

            ServerPlayerEntity target = context.getSource().getServer().getPlayerManager().getPlayer(targetUUID);
            if (target != null) {
                target.sendMessage(Text.literal("Received " + formattedAmount + " (Admin Gift)"), false);
            }
            savage.commoneconomy.util.TransactionLogger.log("ADMIN_GIVE", context.getSource().getName(), displayName, amount, "Admin Gift");
            return 1;
        } else {
            context.getSource().sendError(Text.literal("Transaction failed. Please try again."));
            return 0;
        }
    }

    private static int takeMoney(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        String targetName = StringArgumentType.getString(context, "target");
        double amountDouble = DoubleArgumentType.getDouble(context, "amount");
        BigDecimal amount = BigDecimal.valueOf(amountDouble);
        String formattedAmount = EconomyManager.getInstance().format(amount);

        UUID targetUUID = CommandSupport.getTargetUUID(context, targetName);
        String displayName = CommandSupport.getTargetName(context, targetName);

        if (targetUUID == null) {
            context.getSource().sendError(Text.literal("Player not found or has never joined."));
            return 0;
        }

        if (!EconomyManager.getInstance().removeBalance(targetUUID, amount)) {
            context.getSource().sendError(Text.literal("Could not take money (Insufficient funds or transaction failed)."));
            return 0;
        } else {
            CommandSupport.sendCommandFeedback(context, "Took " + formattedAmount + " from " + displayName, true);
            savage.commoneconomy.util.TransactionLogger.log("ADMIN_TAKE", context.getSource().getName(), displayName, amount, "Admin Take");
            return 1;
        }
    }

    private static int setMoney(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        String targetName = StringArgumentType.getString(context, "target");
        double amountDouble = DoubleArgumentType.getDouble(context, "amount");
        BigDecimal amount = BigDecimal.valueOf(amountDouble);
        String formattedAmount = EconomyManager.getInstance().format(amount);

        UUID targetUUID = CommandSupport.getTargetUUID(context, targetName);
        String displayName = CommandSupport.getTargetName(context, targetName);

        if (targetUUID == null) {
            context.getSource().sendError(Text.literal("Player not found or has never joined."));
            return 0;
        }

        EconomyManager.getInstance().setBalance(targetUUID, amount);
        CommandSupport.sendCommandFeedback(context, "Set " + displayName + "'s balance to " + formattedAmount, true);

        ServerPlayerEntity target = context.getSource().getServer().getPlayerManager().getPlayer(targetUUID);
        if (target != null) {
            target.sendMessage(Text.literal("Your balance has been set to " + formattedAmount), false);
        }
        savage.commoneconomy.util.TransactionLogger.log("ADMIN_SET", context.getSource().getName(), displayName, amount, "Set Balance");
        return 1;
    }

    private static int resetMoney(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        String targetName = StringArgumentType.getString(context, "target");

        UUID targetUUID = CommandSupport.getTargetUUID(context, targetName);
        String displayName = CommandSupport.getTargetName(context, targetName);

        if (targetUUID == null) {
            context.getSource().sendError(Text.literal("Player not found or has never joined."));
            return 0;
        }

        EconomyManager.getInstance().resetBalance(targetUUID);
        BigDecimal newBalance = EconomyManager.getInstance().getBalance(targetUUID);
        String formattedAmount = EconomyManager.getInstance().format(newBalance);

        CommandSupport.sendCommandFeedback(context, "Reset " + displayName + "'s balance to " + formattedAmount, true);

        ServerPlayerEntity target = context.getSource().getServer().getPlayerManager().getPlayer(targetUUID);
        if (target != null) {
            target.sendMessage(Text.literal("Your balance has been reset to " + formattedAmount), false);
        }
        return 1;
    }
}
```

- [ ] **Step 5: Update `SavsCommonEconomy` imports and registration**

Replace the command imports (the `import savage.commoneconomy.command.*;` block, currently lines 9–15) with this block — note `EconomyCommands` is removed and `BalanceCommands`, `AdminMoneyCommands`, `EconomyCommand` are added:

```java
import savage.commoneconomy.command.AdminMoneyCommands;
import savage.commoneconomy.command.BalanceCommands;
import savage.commoneconomy.command.BuyCommand;
import savage.commoneconomy.command.DebugCommands;
import savage.commoneconomy.command.DepositCommand;
import savage.commoneconomy.command.EcoCommands;
import savage.commoneconomy.command.EconomyCommand;
import savage.commoneconomy.command.LogCommand;
import savage.commoneconomy.command.SellCommands;
```

Add this import alongside the other `java.util` imports if not present (place it near the top with the other imports):

```java
import java.util.List;
```

Replace the command-registration block (currently the `CommandRegistrationCallback.EVENT.register(...)` lambda registering seven classes, lines 31–39) with a single list-driven loop. Use tabs to match the file:

```java
		// Register commands
		List<EconomyCommand> commands = List.of(
				BalanceCommands::register,
				AdminMoneyCommands::register,
				SellCommands::register,
				BuyCommand::register,
				DepositCommand::register,
				LogCommand::register,
				DebugCommands::register,
				EcoCommands::register);
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				commands.forEach(command -> command.register(dispatcher)));
```

- [ ] **Step 6: Delete `EconomyCommands.java`**

```powershell
git rm src/main/java/savage/commoneconomy/command/EconomyCommands.java
```

- [ ] **Step 7: Confirm no references to `EconomyCommands` remain**

Run:
```powershell
Select-String -Path src\main\java\savage\commoneconomy\*.java,src\main\java\savage\commoneconomy\**\*.java -Pattern 'EconomyCommands'
```
Expected: no matches.

- [ ] **Step 8: Build to verify it compiles and tests pass**

Run:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat build
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 9: Commit**

```powershell
git add src/main/java/savage/commoneconomy/command/EconomyCommand.java src/main/java/savage/commoneconomy/command/CommandSupport.java src/main/java/savage/commoneconomy/command/BalanceCommands.java src/main/java/savage/commoneconomy/command/AdminMoneyCommands.java src/main/java/savage/commoneconomy/SavsCommonEconomy.java
git rm src/main/java/savage/commoneconomy/command/EconomyCommands.java
git commit -m "refactor: split EconomyCommands into Balance/AdminMoney groups

Add EconomyCommand interface and CommandSupport helpers; register command
groups from a single list. Splits the 320-line god-class along the
player/admin seam.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Final verification

- [ ] **All three gaps closed, build green:**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat build
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **No dangling references or swallowed errors:**

```powershell
Select-String -Path src\main\java\savage\commoneconomy\*.java,src\main\java\savage\commoneconomy\**\*.java -Pattern 'EconomyCommands|storage\.SqlStorage|printStackTrace'
```
Expected: no matches except `savage.savdbcore.storage.SqlStorage` (the superclass) inside `SqliteStorage.java`.

- [ ] **Deleted files are gone:**
  - `src/main/java/savage/commoneconomy/storage/SqlStorage.java`
  - `src/main/java/savage/commoneconomy/command/EconomyCommands.java`
