package savage.commoneconomy.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import savage.commoneconomy.EconomyManager;
import savage.commoneconomy.EconomyManager.AccountData;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class JsonStorage implements EconomyStorage {
    private final Map<UUID, AccountData> accounts = new HashMap<>();
    private final File balanceFile;
    private final Gson gson;
    private final EconomyManager manager;

    public JsonStorage(EconomyManager manager) {
        this.manager = manager;
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve("savs-common-economy");
        configDir.toFile().mkdirs();
        this.balanceFile = configDir.resolve("balances.json").toFile();
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    @Override
    public void load() {
        if (balanceFile.exists()) {
            try (FileReader reader = new FileReader(balanceFile)) {
                Type type = new TypeToken<HashMap<UUID, AccountData>>() {}.getType();
                Map<UUID, AccountData> loaded = gson.fromJson(reader, type);
                if (loaded != null) {
                    accounts.putAll(loaded);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void save() {
        try (FileWriter writer = new FileWriter(balanceFile)) {
            gson.toJson(accounts, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public BigDecimal getBalance(UUID uuid) {
        return accounts.containsKey(uuid) ? accounts.get(uuid).balance : manager.getConfig().defaultBalance;
    }

    @Override
    public void setBalance(UUID uuid, BigDecimal amount) {
        AccountData data = accounts.computeIfAbsent(uuid, k -> new AccountData("Unknown", manager.getConfig().defaultBalance));
        data.balance = amount;
        data.version++;
        save();
    }

    @Override
    public synchronized boolean setBalance(UUID uuid, BigDecimal amount, long expectedVersion) {
        AccountData data = accounts.get(uuid);
        if (data == null) {
            // Account doesn't exist, create it
            data = new AccountData("Unknown", manager.getConfig().defaultBalance);
            accounts.put(uuid, data);
        }
        
        if (data.version != expectedVersion) {
            return false; // Optimistic lock failure
        }
        
        data.balance = amount;
        data.version++;
        save();
        return true;
    }

    @Override
    public boolean hasAccount(UUID uuid) {
        return accounts.containsKey(uuid);
    }

    @Override
    public AccountData getAccount(UUID uuid) {
        return accounts.get(uuid);
    }

    @Override
    public void createAccount(UUID uuid, String name) {
        if (!accounts.containsKey(uuid)) {
            accounts.put(uuid, new AccountData(name, manager.getConfig().defaultBalance));
            save();
        } else {
            // Update name if changed
            AccountData data = accounts.get(uuid);
            if (!data.name.equals(name)) {
                data.name = name;
                save();
            }
        }
    }

    @Override
    public UUID getUUID(String name) {
        for (Map.Entry<UUID, AccountData> entry : accounts.entrySet()) {
            if (entry.getValue().name.equalsIgnoreCase(name)) {
                return entry.getKey();
            }
        }
        return null;
    }

    @Override
    public Collection<String> getOfflinePlayerNames() {
        List<String> names = new ArrayList<>();
        for (AccountData data : accounts.values()) {
            names.add(data.name);
        }
        return names;
    }

    @Override
    public void logTransaction(long timestamp, String source, String target, BigDecimal amount, String type, String details) {
        // JsonStorage doesn't handle logging internally, it relies on TransactionLogger's file logging
    }

    @Override
    public List<AccountData> getTopAccounts(int limit) {
        return accounts.values().stream()
                .sorted((a, b) -> b.balance.compareTo(a.balance))
                .limit(limit)
                .collect(Collectors.toList());
    }
    @Override
    public List<savage.commoneconomy.util.TransactionLogger.LogEntry> searchLogs(String target, long cutoffTimestamp) {
        return Collections.emptyList(); // JsonStorage relies on file logging
    }
    @Override
    public void deleteAccount(UUID uuid) {
        accounts.remove(uuid);
        save();
    }
}
