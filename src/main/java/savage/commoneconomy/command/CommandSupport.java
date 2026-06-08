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
