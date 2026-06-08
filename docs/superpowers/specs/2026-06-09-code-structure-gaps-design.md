# Code Structure Gaps — Design

**Date:** 2026-06-09
**Status:** Approved, pending implementation plan

## Context

The mod was reworked into a single-server emerald economy (see
`2026-06-08-server-economy-overhaul-design.md`). That overhaul left three
structural gaps in the code. This spec covers closing them. It is a pure
refactor: no user-facing behavior changes except that database failures now
surface as command errors instead of silently reading as `$0`/empty.

The existing unit test (`economy/PriceBookTest`) must stay green, and
`./gradlew build` must compile cleanly, as the definition of done.

## Gap 1 — Command layer

### Problem

`command/EconomyCommands.java` (~320 lines) is a god-class that bundles two
unrelated responsibilities:

- **Player commands:** `/bal`, `/baltop`, `/pay`, `/withdraw`
- **Admin commands:** `/givemoney`, `/takemoney`, `/setmoney`, `/resetmoney`

Meanwhile `SavsCommonEconomy.onInitialize` hand-registers seven command classes
one statement at a time, with no shared registration pattern.

### Design

Split `EconomyCommands` along its natural player/admin seam:

- `command/BalanceCommands.java` — `/bal`, `/baltop`, `/pay`, `/withdraw`
- `command/AdminMoneyCommands.java` — `/givemoney`, `/takemoney`, `/setmoney`,
  `/resetmoney`

Introduce a minimal functional interface so registration is data, not
boilerplate:

```java
@FunctionalInterface
public interface EconomyCommand {
    void register(CommandDispatcher<ServerCommandSource> dispatcher);
}
```

In `onInitialize`, register from a single list:

```java
List<EconomyCommand> commands = List.of(
    BalanceCommands::register,
    AdminMoneyCommands::register,
    SellCommands::register,
    BuyCommand::register,
    DepositCommand::register,
    LogCommand::register,
    DebugCommands::register,
    EcoCommands::register);
CommandRegistrationCallback.EVENT.register((dispatcher, access, env) ->
        commands.forEach(c -> c.register(dispatcher)));
```

The already-focused single-purpose classes (`BuyCommand`, `DepositCommand`,
`LogCommand`, `SellCommands`, `EcoCommands`, `DebugCommands`) are left as-is.
`EconomyCommands.java` is deleted once its commands move.

### Rejected alternatives

- **One class per command everywhere** — uniform, but high churn for commands
  that are already small and focused. No real gain.
- **Rename-only for plural/singular consistency** — cosmetic; leaves the
  god-class intact.

## Gap 2 — Storage abstraction

### Problem

`storage/EconomyStorage` (interface) ← `storage/SqlStorage` (abstract) ←
`storage/SqliteStorage` (concrete) is a multi-dialect shape. The abstract layer
exists only to support multiple SQL dialects via the `getTransactionsTableCreationSql()`
and `setupDataSource()` hooks. The mod is SQLite-only now (MySQL/Postgres were
deleted in the overhaul), so the abstract/concrete split is dead weight.

### Design

Collapse `SqlStorage` and `SqliteStorage` into a single concrete
`storage/SqliteStorage` that implements `EconomyStorage` and extends savdbcore's
`SqlStorage`. The abstract-method bodies are inlined:

- `getTransactionsTableCreationSql()` was an abstract hook on *our* `SqlStorage`;
  with that layer gone it becomes a private constant / inline string used
  directly in `createTables()`.
- `setupDataSource()` is an abstract method on *savdbcore's* `SqlStorage`, so the
  merged class still overrides it. It stays a no-op (the data-source wiring
  already happens in the constructor), exactly as the current `SqliteStorage`
  does today.

`storage/SqlStorage.java` is deleted.

**Keep the `EconomyStorage` interface.** It is a genuine boundary:
`EconomyManager` depends on it rather than on savdbcore directly, and it is the
natural mock point for future tests. One interface with one implementation is
acceptable when the interface marks a real seam.

### Rejected alternatives

- **Remove the interface too** — saves one file but couples `EconomyManager`
  directly to savdbcore and removes the test seam.
- **Leave as-is** — that is the gap.

## Gap 3 — Error handling

### Problem

`SqlStorage` uses `e.printStackTrace()` throughout, and read failures silently
return defaults: `getBalance()` returns `BigDecimal.ZERO`, `getAccount()`
returns `null`. For an economy, a transient DB error read as `$0` is a
correctness hazard. The same `printStackTrace()` pattern appears in
`EconomyManager` config/worth load+save paths.

### Design (log + fail loudly)

Add an unchecked exception:

```java
public class EconomyStorageException extends RuntimeException {
    public EconomyStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

In the merged `SqliteStorage`:

- Replace every `e.printStackTrace()` with `SavsCommonEconomy.LOGGER.error(...)`.
- **Fail loudly on account/balance reads** — `getBalance`, `getAccount`,
  `hasAccount`, `getUUID`, and both `setBalance` overloads: on `SQLException`,
  log and throw `EconomyStorageException` instead of returning a default. A DB
  fault then surfaces as a Brigadier command error rather than corrupt state.
- Write/secondary paths (`createAccount`, `deleteAccount`, `logTransaction`,
  `getOfflinePlayerNames`, `getTopAccounts`, `searchLogs`) also log via
  `LOGGER.error`. `createAccount`/`deleteAccount`/`logTransaction` rethrow as
  `EconomyStorageException` (they mutate state and must not fail silently);
  read-only listing helpers (`getOfflinePlayerNames`, `getTopAccounts`,
  `searchLogs`) log and return an empty collection, since a partial/empty list
  is safe to display and not balance-affecting.

In `EconomyManager`, replace the `printStackTrace()` calls in `loadConfig`,
`loadWorthConfig`, and `saveWorthConfig` with `LOGGER.error(...)`. These keep
their current fallback behavior (config/worth files are recreated from defaults
on read failure) — they are not balance-critical and have working defaults.

### Behavioral impact

The only runtime behavior change: when the database genuinely fails, a balance
command now reports an error to the player instead of silently showing/acting on
`$0`. Under normal operation nothing changes.

## Definition of done

- `./gradlew build` compiles cleanly (set `JAVA_HOME` first; JDK at
  `C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot`).
- `economy/PriceBookTest` still passes.
- `EconomyCommands.java` and `storage/SqlStorage.java` are deleted; no dangling
  references remain.
- No `printStackTrace()` calls remain in `storage/` or `EconomyManager`.
