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
    public long cooldownRemainingFor(UUID uuid, long nowMillis, int cooldownHours) {
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
