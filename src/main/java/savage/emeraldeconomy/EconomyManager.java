package savage.emeraldeconomy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import savage.emeraldeconomy.config.EconomyConfig;
import savage.emeraldeconomy.config.ItemPrice;
import savage.emeraldeconomy.config.WorthConfig;
import savage.emeraldeconomy.storage.EconomyStorage;
import savage.emeraldeconomy.economy.PriceBook;
import savage.emeraldeconomy.storage.SqliteStorage;

import net.minecraft.server.network.ServerPlayerEntity;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class EconomyManager {
    private static EconomyManager instance;
    private EconomyStorage storage;
    private final Gson gson;
    private EconomyConfig config;

    public EconomyConfig getConfig() {
        return config;
    }

    public static EconomyStorage getStorage() {
        return getInstance().storage;
    }

    public static EconomyManager getInstance() {
        if (instance == null) {
            instance = new EconomyManager();
        }
        return instance;
    }

    // Caching
    private final com.github.benmanes.caffeine.cache.Cache<UUID, AccountData> accountCache;
    private final com.github.benmanes.caffeine.cache.Cache<String, UUID> uuidCache;
    private final com.github.benmanes.caffeine.cache.Cache<String, java.util.List<String>> offlineNamesCache;

    private EconomyManager() {
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        loadConfig();
        
        // Initialize Caches
        this.accountCache = com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                .maximumSize(10000)
                .expireAfterWrite(10, java.util.concurrent.TimeUnit.MINUTES)
                .build();
                
        this.uuidCache = com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                .maximumSize(10000)
                .expireAfterWrite(1, java.util.concurrent.TimeUnit.HOURS)
                .build();
                
        this.offlineNamesCache = com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                .maximumSize(1) // Singleton cache
                .expireAfterWrite(1, java.util.concurrent.TimeUnit.MINUTES)
                .build();
    }
    
    public void initStorage() {
        if (storage != null) return; // Already initialized

        int maxRetries = 10;
        int attempt = 0;

        while (attempt < maxRetries) {
            try {
                storage = new SqliteStorage(this, config.storage.tablePrefix);
                savage.emeraldeconomy.EmeraldEconomy.LOGGER.info("Economy Storage initialized successfully: SQLITE");
                break;
            } catch (Exception e) {
                attempt++;
                savage.emeraldeconomy.EmeraldEconomy.LOGGER.warn(
                        "Failed to initialize economy storage (Attempt " + attempt + "/" + maxRetries + "). Retrying in 2 seconds...", e);

                if (attempt >= maxRetries) {
                    savage.emeraldeconomy.EmeraldEconomy.LOGGER.error(
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

    /**
     * One-time rebrand migration: if the legacy {@code config/savs-common-economy} directory
     * exists and the new {@code config/emerald-economy} one does not, rename it. This preserves
     * the existing config.json (including its saved storage.tablePrefix, so existing balances are
     * not orphaned) and worth.json prices across the Savs -> Emerald rebrand.
     */
    private void migrateLegacyConfigDir() {
        try {
            Path base = FabricLoader.getInstance().getConfigDir();
            File legacy = base.resolve("savs-common-economy").toFile();
            File current = base.resolve("emerald-economy").toFile();
            if (legacy.isDirectory() && !current.exists()) {
                if (legacy.renameTo(current)) {
                    EmeraldEconomy.LOGGER.info("Migrated config dir savs-common-economy -> emerald-economy");
                } else {
                    EmeraldEconomy.LOGGER.warn("Could not migrate legacy config dir; using fresh emerald-economy config");
                }
            }
        } catch (Exception e) {
            EmeraldEconomy.LOGGER.warn("Legacy config dir migration failed; using fresh config", e);
        }
    }

    private void loadConfig() {
        migrateLegacyConfigDir();
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve("emerald-economy").resolve("config.json");
        File configFile = configPath.toFile();

        if (!configFile.exists()) {
            // Ensure directory exists
            configFile.getParentFile().mkdirs();

            this.config = new EconomyConfig();
            try (FileWriter writer = new FileWriter(configFile)) {
                gson.toJson(this.config, writer);
            } catch (IOException e) {
                EmeraldEconomy.LOGGER.error("Failed to write default economy config", e);
            }
        } else {
            try (FileReader reader = new FileReader(configFile)) {
                this.config = gson.fromJson(reader, EconomyConfig.class);
            } catch (IOException e) {
                EmeraldEconomy.LOGGER.error("Failed to read economy config; using defaults", e);
                this.config = new EconomyConfig();
            }
        }

        // Gson leaves a missing or explicitly-null field at null; never let config or its
        // nested blocks be null, so live consumers can dereference them unconditionally.
        if (this.config == null) {
            this.config = new EconomyConfig();
        }
        if (this.config.rewards == null) {
            this.config.rewards = new EconomyConfig.RewardsConfig();
        }
        if (this.config.storage == null) {
            this.config.storage = new EconomyConfig.StorageConfig();
        }
    }

    /** Re-read config.json into memory so /eco reload retunes reward/economy knobs live. */
    public void reloadConfig() {
        loadConfig();
    }

    public void load() {
        storage.load();
    }

    public void save() {
        storage.save();
    }

    public BigDecimal getBalance(UUID uuid) {
        AccountData data = accountCache.getIfPresent(uuid);
        if (data != null) {
            return data.balance;
        }
        
        // Cache miss, load from storage
        BigDecimal balance = storage.getBalance(uuid);
        // We need version to cache properly, but getBalance only returns BigDecimal.
        // Ideally we should use getAccountData to populate cache.
        // For now, let's fetch full data if possible, or just cache the balance with a dummy version/name if we can't get full data easily without changing storage interface again.
        // Actually, we added getAccount to storage interface earlier!
        data = storage.getAccount(uuid);
        if (data != null) {
            accountCache.put(uuid, data);
            return data.balance;
        } else {
            // Account doesn't exist, return default but don't cache null unless we use Optional
            return config.defaultBalance;
        }
    }

    private net.minecraft.server.MinecraftServer server;

    public void setServer(net.minecraft.server.MinecraftServer server) {
        this.server = server;
    }

    public net.minecraft.server.MinecraftServer getServer() {
        return server;
    }

    public void setBalance(UUID uuid, BigDecimal amount) {
        storage.setBalance(uuid, amount);
        accountCache.invalidate(uuid);
    }

    public record DepositResult(int emeralds, BigDecimal net, BigDecimal fee, int feePercent, boolean ok) {}

    /** Convert {@code count} emeralds to balance minus the deposit fee. Credits balance first. */
    public DepositResult depositEmeralds(UUID uuid, int count) {
        int feePercent = Math.max(0, Math.min(100, config.depositFeePercent));
        BigDecimal gross = BigDecimal.valueOf(count);
        BigDecimal net = gross.multiply(BigDecimal.valueOf(100 - feePercent))
                .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.DOWN);
        BigDecimal fee = gross.subtract(net);
        boolean ok = addBalance(uuid, net);
        return new DepositResult(count, net, fee, feePercent, ok);
    }

    public enum TransferStatus { OK, SELF, INSUFFICIENT_FUNDS, FAILED }

    /** Move {@code amount} from one account to another. Debits source first, then credits target. */
    public TransferStatus transfer(UUID from, UUID to, BigDecimal amount) {
        if (from.equals(to)) return TransferStatus.SELF;
        if (!removeBalance(from, amount)) return TransferStatus.INSUFFICIENT_FUNDS;
        if (!addBalance(to, amount)) {
            // Credit failed after the debit succeeded: refund the sender so money is never
            // destroyed, log loudly, and report failure to the caller.
            boolean refunded = addBalance(from, amount);
            EmeraldEconomy.LOGGER.error(
                    "Transfer credit failed after debit (from={} to={} amount={}); sender refund {}",
                    from, to, amount, refunded ? "succeeded" : "ALSO FAILED — manual correction needed");
            return TransferStatus.FAILED;
        }
        return TransferStatus.OK;
    }

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
        savage.emeraldeconomy.util.TransactionLogger.log("WITHDRAW", player.getName().getString(), "Emeralds", cost, "Withdrawal");
        return new WithdrawResult(WithdrawStatus.OK, emeralds);
    }

    public boolean addBalance(UUID uuid, BigDecimal amount) {
        int retries = 10;
        while (retries > 0) {
            // Reload account data to get latest version
            AccountData data = getAccountData(uuid); 
            BigDecimal current = data != null ? data.balance : config.defaultBalance;
            long version = data != null ? data.version : 0;
            
            if (storage.setBalance(uuid, current.add(amount), version)) {
                // Update cache on success
                if (data != null) {
                    data.balance = current.add(amount);
                    data.version++;
                    accountCache.put(uuid, data);
                } else {
                    accountCache.invalidate(uuid);
                }
                
                return true;
            }
            // On failure, invalidate cache to ensure we get fresh data from DB next retry
            accountCache.invalidate(uuid);
            
            retries--;
            try {
                Thread.sleep(10 + (long)(Math.random() * 10)); // Small random backoff
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return false; // Failed after retries
    }

    public boolean removeBalance(UUID uuid, BigDecimal amount) {
        int retries = 10;
        while (retries > 0) {
            // Reload account data to get latest version
            AccountData data = getAccountData(uuid);
            BigDecimal current = data != null ? data.balance : config.defaultBalance;
            long version = data != null ? data.version : 0;
            
            if (current.compareTo(amount) >= 0) {
                if (storage.setBalance(uuid, current.subtract(amount), version)) {
                     // Update cache on success
                    if (data != null) {
                        data.balance = current.subtract(amount);
                        data.version++;
                        accountCache.put(uuid, data);
                    } else {
                        accountCache.invalidate(uuid);
                    }
                    
                    return true;
                }
            } else {
                return false; // Insufficient funds
            }
            // On failure, invalidate cache
            accountCache.invalidate(uuid);
            
            retries--;
            try {
                Thread.sleep(10 + (long)(Math.random() * 10)); // Small random backoff
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return false; // Failed after retries
    }
    
    private AccountData getAccountData(UUID uuid) {
        AccountData data = accountCache.getIfPresent(uuid);
        if (data != null) {
            return data;
        }
        
        data = storage.getAccount(uuid);
        if (data != null) {
            accountCache.put(uuid, data);
        }
        return data;
    }

    public boolean hasAccount(UUID uuid) {
        if (accountCache.getIfPresent(uuid) != null) return true;
        return storage.hasAccount(uuid);
    }

    public void createAccount(UUID uuid, String name) {
        storage.createAccount(uuid, name);
        // Cache the new account
        accountCache.put(uuid, new AccountData(name, config.defaultBalance, 0));
        uuidCache.put(name.toLowerCase(), uuid);
        offlineNamesCache.invalidateAll(); // Invalidate names list
    }

    public void deleteAccount(UUID uuid) {
        storage.deleteAccount(uuid);
        // Invalidate all caches
        accountCache.invalidate(uuid);
        AccountData data = accountCache.getIfPresent(uuid);
        if (data != null) {
            uuidCache.invalidate(data.name.toLowerCase());
        }
        offlineNamesCache.invalidateAll();
    }
    
    public void invalidateCache(UUID uuid) {
        accountCache.invalidate(uuid);
    }

    public void resetBalance(UUID uuid) {
        setBalance(uuid, config.defaultBalance);
    }

    public UUID getUUID(String name) {
        UUID uuid = uuidCache.getIfPresent(name.toLowerCase());
        if (uuid != null) return uuid;
        
        uuid = storage.getUUID(name);
        if (uuid != null) {
            uuidCache.put(name.toLowerCase(), uuid);
        }
        return uuid;
    }

    public java.util.Collection<String> getOfflinePlayerNames() {
        java.util.List<String> names = offlineNamesCache.getIfPresent("all");
        if (names != null) return names;
        
        names = new java.util.ArrayList<>(storage.getOfflinePlayerNames());
        offlineNamesCache.put("all", names);
        return names;
    }

    public String format(BigDecimal amount) {
        return config.currencySymbol + amount.toString();
    }

    // Leaderboard support
    public java.util.List<AccountData> getTopAccounts(int limit) {
        return storage.getTopAccounts(limit);
    }

    // Pricing support
    private WorthConfig worthConfig;
    private PriceBook priceBook;

    private PriceBook priceBook() {
        if (priceBook == null) {
            if (worthConfig == null) {
                loadWorthConfig();
            }
            priceBook = new PriceBook(worthConfig.flatten());
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

    public boolean isSellable(String itemId) {
        return priceBook().isSellable(itemId);
    }

    /** The physical currency item id. Emerald is currency, not a tradeable good. */
    public static final String CURRENCY_ITEM_ID = "minecraft:emerald";

    public boolean isCurrencyItem(String itemId) {
        return CURRENCY_ITEM_ID.equals(itemId);
    }

    /** All curated item prices, flattened across categories (for /worth list and /buy suggestions). */
    public Map<String, ItemPrice> getAllItemPrices() {
        if (worthConfig == null) {
            loadWorthConfig();
        }
        return worthConfig.flatten();
    }

    /** Categories -> buyable items (buy price set), preserving worth.json order. Excludes currency. */
    public Map<String, Map<String, ItemPrice>> getBuyableCategories() {
        if (worthConfig == null) loadWorthConfig();
        Map<String, Map<String, ItemPrice>> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Map<String, ItemPrice>> cat : worthConfig.categories.entrySet()) {
            Map<String, ItemPrice> kept = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, ItemPrice> e : cat.getValue().entrySet()) {
                if (isCurrencyItem(e.getKey())) continue;
                if (e.getValue().buy != null) kept.put(e.getKey(), e.getValue());
            }
            if (!kept.isEmpty()) out.put(cat.getKey(), kept);
        }
        return out;
    }

    private File worthFile() {
        return FabricLoader.getInstance().getConfigDir()
                .resolve("emerald-economy").resolve("worth.json").toFile();
    }

    private void loadWorthConfig() {
        File worthFile = worthFile();

        if (!worthFile.exists()) {
            this.worthConfig = WorthConfig.createDefault();
            saveWorthConfig();
        } else {
            try (FileReader reader = new FileReader(worthFile)) {
                this.worthConfig = gson.fromJson(reader, WorthConfig.class);
            } catch (IOException e) {
                EmeraldEconomy.LOGGER.error("Failed to read worth.json; using empty config", e);
                this.worthConfig = new WorthConfig();
            }
            // Gson returns null for an empty/blank worth.json; never leave it null.
            if (this.worthConfig == null) {
                this.worthConfig = new WorthConfig();
            }
            // Normalise null maps and migrate any legacy flat itemPrices into categories.
            if (this.worthConfig.normalize()) {
                saveWorthConfig(); // upgrade old flat file to the nested format
            }
        }
    }

    /** Write worth.json, backing up the previous file to worth.json.bak first. */
    public void saveWorthConfig() {
        if (worthConfig == null) return;
        File worthFile = worthFile();
        File backup = FabricLoader.getInstance().getConfigDir()
                .resolve("emerald-economy").resolve("worth.json.bak").toFile();
        try {
            if (worthFile.exists()) {
                java.nio.file.Files.copy(worthFile.toPath(), backup.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            EmeraldEconomy.LOGGER.error("Failed to back up worth.json", e);
        }
        worthFile.getParentFile().mkdirs();
        try (FileWriter writer = new FileWriter(worthFile)) {
            gson.toJson(this.worthConfig, writer);
        } catch (IOException e) {
            EmeraldEconomy.LOGGER.error("Failed to write worth.json", e);
        }
    }

    /** Reload worth.json from disk and rebuild the price lookup (no restart needed). */
    public void reloadPrices() {
        this.worthConfig = null;
        this.priceBook = null;
        loadWorthConfig();
    }

    /**
     * Add a price-less entry (buy = null, sell = null) for every item id not already
     * listed, placing it in the given creative-tab category. Skips air and the currency
     * item. Saves worth.json (with backup) and rebuilds the price lookup. Returns the
     * number of new items added. Admins fill in real prices by editing worth.json.
     */
    public int generatePrices(Map<String, String> idToCategory) {
        if (worthConfig == null) {
            loadWorthConfig();
        }
        int added = 0;
        for (Map.Entry<String, String> entry : idToCategory.entrySet()) {
            String id = entry.getKey();
            if (id.equals("minecraft:air") || isCurrencyItem(id)) {
                continue;
            }
            if (worthConfig.contains(id)) {
                continue;
            }
            String category = entry.getValue() != null ? entry.getValue() : "uncategorized";
            worthConfig.categories.computeIfAbsent(category, k -> new java.util.LinkedHashMap<>())
                    .put(id, new ItemPrice(null, null));
            added++;
        }
        saveWorthConfig();
        this.priceBook = null; // rebuild lazily with the new entries
        return added;
    }

    public static class AccountData {
        public String name;
        public BigDecimal balance;
        public long version;

        public AccountData(String name, BigDecimal balance) {
            this(name, balance, 0);
        }

        public AccountData(String name, BigDecimal balance, long version) {
            this.name = name;
            this.balance = balance;
            this.version = version;
        }
    }
}
