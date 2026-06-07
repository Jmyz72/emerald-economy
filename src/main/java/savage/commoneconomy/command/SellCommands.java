package savage.commoneconomy.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import savage.commoneconomy.EconomyManager;

import java.math.BigDecimal;
import java.util.Map;

public class SellCommands {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("worth")
                .requires(source -> savage.commoneconomy.util.PermissionsHelper.check(source, "savscommoneconomy.command.worth", true))
                .executes(SellCommands::checkHandWorth)
                .then(CommandManager.literal("all")
                        .executes(SellCommands::checkAllWorth))
                .then(CommandManager.literal("list")
                        .executes(SellCommands::listWorth))
                .then(CommandManager.argument("item", StringArgumentType.string())
                        .executes(SellCommands::checkItemWorth)));

        dispatcher.register(CommandManager.literal("sell")
                .requires(source -> savage.commoneconomy.util.PermissionsHelper.check(source, "savscommoneconomy.command.sell", true))
                .executes(SellCommands::sellHand)
                .then(CommandManager.literal("all")
                        .executes(SellCommands::sellAll)));
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
        BigDecimal sell = EconomyManager.getInstance().getSellPrice(itemId);
        BigDecimal buy = EconomyManager.getInstance().getBuyPrice(itemId);
        boolean buyable = EconomyManager.getInstance().isBuyable(itemId);

        BigDecimal stackValue = sell.multiply(BigDecimal.valueOf(stack.getCount()));
        String buyText = buyable ? EconomyManager.getInstance().format(buy) : "not buyable";
        context.getSource().sendFeedback(() -> Text.literal(
                stack.getCount() + "x " + itemId
                        + " | Sell: " + EconomyManager.getInstance().format(sell) + " each (total " + EconomyManager.getInstance().format(stackValue) + ")"
                        + " | Buy: " + buyText + " each"), false);
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
        BigDecimal price = EconomyManager.getInstance().getSellPrice(itemId);

        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            context.getSource().sendError(Text.literal("This item cannot be sold."));
            return 0;
        }

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
        String itemId = StringArgumentType.getString(context, "item");
        if (EconomyManager.getInstance().isCurrencyItem(itemId)) {
            context.getSource().sendFeedback(() -> Text.literal(
                    itemId + " is currency (1 emerald = $1). Use /withdraw and /deposit."), false);
            return 1;
        }
        BigDecimal sell = EconomyManager.getInstance().getSellPrice(itemId);
        BigDecimal buy = EconomyManager.getInstance().getBuyPrice(itemId);
        boolean buyable = EconomyManager.getInstance().isBuyable(itemId);
        String buyText = buyable ? EconomyManager.getInstance().format(buy) : "not buyable";
        context.getSource().sendFeedback(() -> Text.literal(
                itemId + " | Sell: " + EconomyManager.getInstance().format(sell) + " each | Buy: " + buyText + " each"), false);
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
        if (EconomyManager.getInstance().isCurrencyItem(itemId)) {
            context.getSource().sendError(Text.literal("Emeralds are currency — use /deposit instead."));
            return 0;
        }
        BigDecimal price = EconomyManager.getInstance().getSellPrice(itemId);

        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            context.getSource().sendError(Text.literal("This item cannot be sold."));
            return 0;
        }

        int count = stack.getCount();
        BigDecimal totalValue = price.multiply(BigDecimal.valueOf(count));

        if (EconomyManager.getInstance().addBalance(player.getUuid(), totalValue)) {
            player.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, ItemStack.EMPTY);
            context.getSource().sendFeedback(() -> Text.literal("Sold " + count + "x " + itemId + " for " + EconomyManager.getInstance().format(totalValue)), false);
            

            
            savage.commoneconomy.util.TransactionLogger.log("COMMAND_SELL", player.getName().getString(), "Server", totalValue, "Sold " + count + "x " + itemId);
            return 1;
        } else {
            context.getSource().sendError(Text.literal("Transaction failed. Please try again."));
            return 0;
        }
    }

    private static int sellAll(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
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
        BigDecimal price = EconomyManager.getInstance().getSellPrice(itemId);

        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            context.getSource().sendError(Text.literal("This item cannot be sold."));
            return 0;
        }

        int totalCount = 0;
        // Calculate total count first
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getItem() == handStack.getItem()) {
                totalCount += stack.getCount();
            }
        }

        BigDecimal totalValue = price.multiply(BigDecimal.valueOf(totalCount));
        
        if (EconomyManager.getInstance().addBalance(player.getUuid(), totalValue)) {
            // Only remove items if transaction succeeded
            for (int i = 0; i < player.getInventory().size(); i++) {
                ItemStack stack = player.getInventory().getStack(i);
                if (!stack.isEmpty() && stack.getItem() == handStack.getItem()) {
                    player.getInventory().setStack(i, ItemStack.EMPTY);
                }
            }
            
            int finalTotalCount = totalCount;
            context.getSource().sendFeedback(() -> Text.literal("Sold all " + finalTotalCount + "x " + itemId + " for " + EconomyManager.getInstance().format(totalValue)), false);
            

            
            savage.commoneconomy.util.TransactionLogger.log("COMMAND_SELL", player.getName().getString(), "Server", totalValue, "Sold all " + finalTotalCount + "x " + itemId);
            return 1;
        } else {
            context.getSource().sendError(Text.literal("Transaction failed. Please try again."));
            return 0;
        }
    }
}
