package savage.commoneconomy.storage;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import savage.commoneconomy.EconomyManager.AccountData;

public interface EconomyStorage {
    void load();
    void save();
    
    BigDecimal getBalance(UUID uuid);
    void setBalance(UUID uuid, BigDecimal amount);
    boolean setBalance(UUID uuid, BigDecimal amount, long expectedVersion);
    
    boolean hasAccount(UUID uuid);
    AccountData getAccount(UUID uuid);
    void createAccount(UUID uuid, String name);
    
    UUID getUUID(String name);
    Collection<String> getOfflinePlayerNames();
    List<AccountData> getTopAccounts(int limit);
    
    void logTransaction(long timestamp, String source, String target, BigDecimal amount, String type, String details);
    
    List<savage.commoneconomy.util.TransactionLogger.LogEntry> searchLogs(String target, long cutoffTimestamp);
    
    void deleteAccount(UUID uuid);
}
