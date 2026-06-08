package savage.commoneconomy.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import savage.commoneconomy.EconomyManager;

import java.math.BigDecimal;
import java.util.Map;

public class SellCommands {

    private static final com.mojang.brigadier.suggestion.SuggestionProvider<ServerCommandSource> SELLABLE_SUGGESTIONS =
            (context, builder) -> net.minecraft.command.CommandSource.suggestMatching(
                    EconomyManager.getInstance().getAllItemPrices().keySet().stream()
                            .filter(id -> EconomyManager.getInstance().isSellable(id))
                            .toList(),
                    builder);

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("worth")
                .requires(source -> savage.commoneconomy.util.PermissionsHelper.check(source, "savscommoneconomy.command.worth", true))
                .executes(SellCommands::checkHandWorth)
                .then(CommandManager.literal("all")
                        .executes(SellCommands::checkAllWorth))
                .then(CommandManager.literal("list")
                        .executes(SellCommands::listWorth))
                .then(CommandManager.argument("item", IdentifierArgumentType.identifier())
                        .executes(SellCommands::checkItemWorth)));

        dispatcher.register(CommandManager.literal("sell")
                .requires(source -> savage.commoneconomy.util.PermissionsHelper.check(source, "savscommoneconomy.command.sell", true))
                .executes(SellCommands::sellHand)
                .then(CommandManager.literal("all")
                        .executes(SellCommands::sellAll))
                .then(CommandManager.argument("item", IdentifierArgumentType.identifier())
                        .suggests(SELLABLE_SUGGESTIONS)
                        .executes(ctx -> sellItem(ctx, Integer.MAX_VALUE))
                        .then(CommandManager.literal("all")
                                .executes(ctx -> sellItem(ctx, Integer.MAX_VALUE)))
                        .then(CommandManager.argument("quantity", IntegerArgumentType.integer(1))
                                .executes(ctx -> sellItem(ctx, IntegerArgumentType.getInteger(ctx, "quantity"))))));
    }

    private static int checkHandWorth(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        ItemStack stack = player.getMainHandStack();

        if (stack.isEmpty()) {
            context.getSource().sendError(Text.literal("You are not holding any item."));
            return 0;
        }

        String itemId = Registries.ITEM.getId(stack.getItem()).toString();
        if (EconomyManager.getInstance().isCurrencyItem(itemId)) {
            context.getSource().sendFeedback(() -> Text.literal(
                    itemId + " is currency (1 emerald = $1). Use /withdraw and /deposit."), false);
            return 1;
        }
        boolean sellable = EconomyManager.getInstance().isSellable(itemId);
        boolean buyable = EconomyManager.getInstance().isBuyable(itemId);
        BigDecimal sell = EconomyManager.getInstance().getSellPrice(itemId);
        BigDecimal buy = EconomyManager.getInstance().getBuyPrice(itemId);

        String sellText = sellable
                ? EconomyManager.getInstance().format(sell) + " each (total "
                    + EconomyManager.getInstance().format(sell.multiply(BigDecimal.valueOf(stack.getCount()))) + ")"
                : "not sellable";
        String buyText = buyable ? EconomyManager.getInstance().format(buy) + " each" : "not buyable";
        context.getSource().sendFeedback(() -> Text.literal(
                stack.getCount() + "x " + itemId + " | Sell: " + sellText + " | Buy: " + buyText), false);
        return 1;
    }

    private static int checkAllWorth(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        ItemStack handStack = player.getMainHandStack();

        if (handStack.isEmpty()) {
            context.getSource().sendError(Text.literal("You are not holding any item."));
            return 0;
        }

        String itemId = Registries.ITEM.getId(handStack.getItem()).toString();
        if (EconomyManager.getInstance().isCurrencyItem(itemId)) {
            context.getSource().sendError(Text.literal("Emeralds are currency — use /deposit instead."));
            return 0;
        }
        if (!EconomyManager.getInstance().isSellable(itemId)) {
            context.getSource().sendError(Text.literal("This item cannot be sold."));
            return 0;
        }
        BigDecimal price = EconomyManager.getInstance().getSellPrice(itemId);

        int totalCount = 0;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getItem() == handStack.getItem()) {
                totalCount += stack.getCount();
            }
        }

        BigDecimal totalValue = price.multiply(BigDecimal.valueOf(totalCount));
        int finalTotalCount = totalCount;
        context.getSource().sendFeedback(() -> Text.literal("Worth of all " + finalTotalCount + "x " + itemId + " in inventory: " + EconomyManager.getInstance().format(totalValue)), false);
        return 1;
    }

    private static int listWorth(CommandContext<ServerCommandSource> context) {
        Map<String, savage.commoneconomy.config.ItemPrice> prices = EconomyManager.getInstance().getAllItemPrices();
        if (prices.isEmpty()) {
            context.getSource().sendFeedback(() -> Text.literal("No curated item prices are configured."), false);
            return 1;
        }

        context.getSource().sendFeedback(() -> Text.literal("Curated Item Prices (buy / sell):"), false);
        for (Map.Entry<String, savage.commoneconomy.config.ItemPrice> entry : prices.entrySet()) {
            savage.commoneconomy.config.ItemPrice p = entry.getValue();
            String buy = p.buy != null ? EconomyManager.getInstance().format(p.buy) : "-";
            String sell = p.sell != null ? EconomyManager.getInstance().format(p.sell) : "-";
            context.getSource().sendFeedback(() -> Text.literal("- " + entry.getKey() + ": " + buy + " / " + sell), false);
        }
        return 1;
    }

    private static int checkItemWorth(CommandContext<ServerCommandSource> context) {
        Identifier id = IdentifierArgumentType.getIdentifier(context, "item");
        String itemId = id.toString();
        if (EconomyManager.getInstance().isCurrencyItem(itemId)) {
            context.getSource().sendFeedback(() -> Text.literal(
                    itemId + " is currency (1 emerald = $1). Use /withdraw and /deposit."), false);
            return 1;
        }
        if (!Registries.ITEM.containsId(id)) {
            context.getSource().sendError(Text.literal("Unknown item: " + itemId));
            return 0;
        }
        boolean sellable = EconomyManager.getInstance().isSellable(itemId);
        boolean buyable = EconomyManager.getInstance().isBuyable(itemId);
        BigDecimal sell = EconomyManager.getInstance().getSellPrice(itemId);
        BigDecimal buy = EconomyManager.getInstance().getBuyPrice(itemId);
        String sellText = sellable ? EconomyManager.getInstance().format(sell) + " each" : "not sellable";
        String buyText = buyable ? EconomyManager.getInstance().format(buy) + " each" : "not buyable";
        context.getSource().sendFeedback(() -> Text.literal(
                itemId + " | Sell: " + sellText + " | Buy: " + buyText), false);
        return 1;
    }

    private static int sellHand(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        ItemStack stack = player.getMainHandStack();

        if (stack.isEmpty()) {
            context.getSource().sendError(Text.literal("You are not holding any item."));
            return 0;
        }

        String itemId = Registries.ITEM.getId(stack.getItem()).toString();
        savage.commoneconomy.economy.TradeService.Result r =
                savage.commoneconomy.economy.TradeService.sell(player, itemId, stack.getCount());
        return reportSell(context, r, itemId);
    }

    private static int sellAll(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        ItemStack handStack = player.getMainHandStack();

        if (handStack.isEmpty()) {
            context.getSource().sendError(Text.literal("You are not holding any item."));
            return 0;
        }

        String itemId = Registries.ITEM.getId(handStack.getItem()).toString();
        savage.commoneconomy.economy.TradeService.Result r =
                savage.commoneconomy.economy.TradeService.sell(player, itemId, Integer.MAX_VALUE);
        return reportSell(context, r, itemId);
    }

    private static int sellItem(CommandContext<ServerCommandSource> context, int maxAmount) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        Identifier id = IdentifierArgumentType.getIdentifier(context, "item");
        String itemId = id.toString();
        if (!Registries.ITEM.containsId(id)) {
            context.getSource().sendError(Text.literal("Unknown item: " + itemId));
            return 0;
        }
        savage.commoneconomy.economy.TradeService.Result r =
                savage.commoneconomy.economy.TradeService.sell(player, itemId, maxAmount);
        return reportSell(context, r, itemId);
    }

    private static int reportSell(CommandContext<ServerCommandSource> context,
                                  savage.commoneconomy.economy.TradeService.Result r, String itemId) {
        switch (r.status()) {
            case IS_CURRENCY -> context.getSource().sendError(Text.literal("Emeralds are currency — use /deposit instead."));
            case NOT_TRADEABLE -> context.getSource().sendError(Text.literal("This item cannot be sold."));
            case NONE_HELD -> context.getSource().sendError(Text.literal("You have none of that item."));
            case OK -> { context.getSource().sendFeedback(() -> Text.literal("Sold " + r.amount() + "x " + itemId
                    + " for " + EconomyManager.getInstance().format(r.total())), false); return 1; }
            default -> context.getSource().sendError(Text.literal("Transaction failed. Please try again."));
        }
        return 0;
    }
}
