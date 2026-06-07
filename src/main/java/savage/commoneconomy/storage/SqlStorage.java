package savage.commoneconomy.storage;

import savage.commoneconomy.EconomyManager.AccountData;

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
 * Economy-specific SQL storage base class.
 * Extends savdbcore's SqlStorage and adds economy-specific functionality.
 */
public abstract class SqlStorage extends savage.savdbcore.storage.SqlStorage implements EconomyStorage {
    protected final savage.commoneconomy.EconomyManager manager;

    public SqlStorage(savage.commoneconomy.EconomyManager manager, String tablePrefix) {
        super(tablePrefix);
        this.manager = manager;
    }

    protected void createTables() {
        try (Connection conn = getConnection()) {
            // Create accounts table
            try (PreparedStatement stmt = conn.prepareStatement(
                     "CREATE TABLE IF NOT EXISTS " + tablePrefix + "accounts (" +
                             "uuid VARCHAR(36) PRIMARY KEY, " +
                             "name VARCHAR(16) NOT NULL, " +
                             "balance DECIMAL(20, 2) NOT NULL, " +
                             "version BIGINT DEFAULT 0" +
                             ")")) {
                stmt.executeUpdate();
            }
            
            // Migration: Add version column if it doesn't exist
            try (PreparedStatement checkStmt = conn.prepareStatement("SELECT version FROM " + tablePrefix + "accounts LIMIT 1")) {
                checkStmt.executeQuery();
            } catch (SQLException e) {
                // Column likely missing, try to add it
                try (PreparedStatement alterStmt = conn.prepareStatement("ALTER TABLE " + tablePrefix + "accounts ADD COLUMN version BIGINT DEFAULT 0")) {
                    alterStmt.executeUpdate();
                } catch (SQLException ex) {
                    ex.printStackTrace();
                }
            }

            // Create transactions table
            try (PreparedStatement stmt = conn.prepareStatement(getTransactionsTableCreationSql())) {
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    protected abstract String getTransactionsTableCreationSql();

    @Override
    public void load() {
        initialize(); // Call savdbcore's initialize
        createTables();
    }

    @Override
    public void save() {
        shutdown(); // Call savdbcore's shutdown
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
            e.printStackTrace();
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
            e.printStackTrace();
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
            e.printStackTrace();
        }
        return false;
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
            e.printStackTrace();
        }
        return false;
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
                            rs.getLong("version")
                    );
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public void createAccount(UUID uuid, String name) {
        if (hasAccount(uuid)) {
            // Update name
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "UPDATE " + tablePrefix + "accounts SET name = ? WHERE uuid = ?")) {
                stmt.setString(1, name);
                stmt.setString(2, uuid.toString());
                stmt.executeUpdate();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        } else {
            // Insert new
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "INSERT INTO " + tablePrefix + "accounts (uuid, name, balance, version) VALUES (?, ?, ?, 0)")) {
                stmt.setString(1, uuid.toString());
                stmt.setString(2, name);
                stmt.setBigDecimal(3, manager.getConfig().defaultBalance);
                stmt.executeUpdate();
            } catch (SQLException e) {
                e.printStackTrace();
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
            e.printStackTrace();
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
            e.printStackTrace();
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
            e.printStackTrace();
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
                            rs.getString("details")
                    ));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
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
                            rs.getLong("version")
                    ));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return accounts;
    }

    @Override
    public void deleteAccount(UUID uuid) {
        String sql = "DELETE FROM " + tablePrefix + "accounts WHERE uuid = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
