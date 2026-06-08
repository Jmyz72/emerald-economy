package savage.commoneconomy.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import savage.commoneconomy.EconomyManager;

public class BuyCommand {

    private static final SuggestionProvider<ServerCommandSource> ITEM_SUGGESTIONS = (context, builder) ->
            CommandSource.suggestMatching(
                    EconomyManager.getInstance().getAllItemPrices().keySet().stream()
                            .filter(id -> EconomyManager.getInstance().isBuyable(id))
                            .toList(),
                    builder);

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("buy")
                .requires(source -> savage.commoneconomy.util.PermissionsHelper.check(source, "savscommoneconomy.command.buy", true))
                .then(CommandManager.argument("item", IdentifierArgumentType.identifier())
                        .suggests(ITEM_SUGGESTIONS)
                        .then(CommandManager.argument("amount", IntegerArgumentType.integer(1))
                                .executes(BuyCommand::buy))));
    }

    private static int buy(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        Identifier id = IdentifierArgumentType.getIdentifier(context, "item");
        String itemId = id.toString();
        int amount = IntegerArgumentType.getInteger(context, "amount");

        savage.commoneconomy.economy.TradeService.Result r =
                savage.commoneconomy.economy.TradeService.buy(player, itemId, amount);
        switch (r.status()) {
            case UNKNOWN_ITEM -> { context.getSource().sendError(Text.literal("Unknown item: " + itemId)); return 0; }
            case IS_CURRENCY -> { context.getSource().sendError(Text.literal("Emeralds are currency — use /withdraw instead.")); return 0; }
            case NOT_TRADEABLE -> { context.getSource().sendError(Text.literal("This item cannot be bought.")); return 0; }
            case INSUFFICIENT_FUNDS -> { context.getSource().sendError(Text.literal("Insufficient funds.")); return 0; }
            case OK -> {
                context.getSource().sendFeedback(() -> Text.literal("Bought " + r.amount() + "x " + itemId
                        + " for " + EconomyManager.getInstance().format(r.total())), false);
                return 1;
            }
            default -> { context.getSource().sendError(Text.literal("Purchase failed. Please try again.")); return 0; }
        }
    }
}
