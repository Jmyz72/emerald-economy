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
