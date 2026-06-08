package savage.commoneconomy.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import savage.commoneconomy.EconomyManager;

import java.math.BigDecimal;

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

        if (!Registries.ITEM.containsId(id)) {
            context.getSource().sendError(Text.literal("Unknown item: " + itemId));
            return 0;
        }

        if (EconomyManager.getInstance().isCurrencyItem(itemId)) {
            context.getSource().sendError(Text.literal("Emeralds are currency — use /withdraw instead."));
            return 0;
        }

        if (!EconomyManager.getInstance().isBuyable(itemId)) {
            context.getSource().sendError(Text.literal("This item cannot be bought."));
            return 0;
        }

        Item item = Registries.ITEM.get(id);
        BigDecimal unitPrice = EconomyManager.getInstance().getBuyPrice(itemId);
        BigDecimal totalCost = unitPrice.multiply(BigDecimal.valueOf(amount));

        if (!EconomyManager.getInstance().removeBalance(player.getUuid(), totalCost)) {
            context.getSource().sendError(Text.literal("Insufficient funds. Cost: "
                    + EconomyManager.getInstance().format(totalCost)));
            return 0;
        }

        int remaining = amount;
        int maxStack = new ItemStack(item).getMaxCount(); // 1.20.5+: stack size is a data component, not Item.getMaxCount()
        while (remaining > 0) {
            int give = Math.min(remaining, maxStack);
            player.getInventory().offerOrDrop(new ItemStack(item, give));
            remaining -= give;
        }

        final int boughtAmount = amount;
        context.getSource().sendFeedback(() -> Text.literal("Bought " + boughtAmount + "x " + itemId
                + " for " + EconomyManager.getInstance().format(totalCost)), false);
        savage.commoneconomy.util.TransactionLogger.log("COMMAND_BUY", player.getName().getString(), "Server",
                totalCost, "Bought " + boughtAmount + "x " + itemId);
        return 1;
    }
}
