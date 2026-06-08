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
