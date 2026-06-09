package savage.emeraldeconomy.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import savage.emeraldeconomy.EconomyManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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

    /**
     * Suggest item ids, matching the typed text against BOTH the full id and the bare
     * path. Vanilla {@code suggestMatching} only matches the start of the full id, so
     * typing "elytra" would suggest nothing; here it suggests "minecraft:elytra".
     */
    static CompletableFuture<Suggestions> suggestItems(SuggestionsBuilder builder, Collection<String> ids) {
        String rem = builder.getRemaining().toLowerCase(Locale.ROOT);
        for (String id : ids) {
            int colon = id.indexOf(':');
            String path = colon >= 0 ? id.substring(colon + 1) : id;
            if (id.startsWith(rem) || path.startsWith(rem)) {
                builder.suggest(id);
            }
        }
        return builder.buildFuture();
    }
}
