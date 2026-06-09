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

        // Fabric command callbacks run on the server thread, and addBalance blocks on it, so this
        // check -> pay -> markClaimed sequence runs atomically per player; no double-claim window.
        long now = System.currentTimeMillis();
        long remaining = DailyRewards.getInstance().cooldownRemainingFor(uuid, now, rewards.dailyCooldownHours);
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
