package savage.commoneconomy.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import savage.commoneconomy.EconomyManager;

import java.math.BigDecimal;
import java.util.UUID;

/** Player-facing balance commands: /bal, /baltop, /pay, /withdraw. */
public class BalanceCommands {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("bal")
                .requires(source -> savage.commoneconomy.util.PermissionsHelper.check(source, "savscommoneconomy.command.bal", true))
                .executes(BalanceCommands::checkSelfBalance)
                .then(CommandManager.argument("target", StringArgumentType.string())
                        .requires(source -> savage.commoneconomy.util.PermissionsHelper.check(source, "savscommoneconomy.command.bal.others", true))
                        .suggests(CommandSupport.PLAYER_SUGGESTION_PROVIDER)
                        .executes(BalanceCommands::checkOtherBalance)));

        dispatcher.register(CommandManager.literal("baltop")
                .requires(source -> savage.commoneconomy.util.PermissionsHelper.check(source, "savscommoneconomy.command.baltop", true))
                .executes(BalanceCommands::balTop));

        dispatcher.register(CommandManager.literal("pay")
                .requires(source -> savage.commoneconomy.util.PermissionsHelper.check(source, "savscommoneconomy.command.pay", true))
                .then(CommandManager.argument("target", StringArgumentType.string())
                        .suggests(CommandSupport.PLAYER_SUGGESTION_PROVIDER)
                        .then(CommandManager.argument("amount", DoubleArgumentType.doubleArg(0))
                                .executes(BalanceCommands::pay))));

        dispatcher.register(CommandManager.literal("withdraw")
                .requires(source -> savage.commoneconomy.util.PermissionsHelper.check(source, "savscommoneconomy.command.withdraw", true))
                .then(CommandManager.argument("amount", DoubleArgumentType.doubleArg(1))
                        .executes(BalanceCommands::withdraw)));
    }

    private static int balTop(CommandContext<ServerCommandSource> context) {
        java.util.List<EconomyManager.AccountData> topAccounts = EconomyManager.getInstance().getTopAccounts(10);

        context.getSource().sendFeedback(() -> Text.literal("--- Balance Top 10 ---"), false);
        for (int i = 0; i < topAccounts.size(); i++) {
            EconomyManager.AccountData account = topAccounts.get(i);
            int rank = i + 1;
            context.getSource().sendFeedback(() -> Text.literal(rank + ". " + account.name + ": " + EconomyManager.getInstance().format(account.balance)), false);
        }
        return 1;
    }

    private static int checkSelfBalance(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        BigDecimal balance = EconomyManager.getInstance().getBalance(player.getUuid());
        context.getSource().sendFeedback(() -> Text.literal("Your balance: " + EconomyManager.getInstance().format(balance)), false);
        return 1;
    }

    private static int checkOtherBalance(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        String targetName = StringArgumentType.getString(context, "target");
        UUID targetUUID = CommandSupport.getTargetUUID(context, targetName);
        String displayName = CommandSupport.getTargetName(context, targetName);

        if (targetUUID == null) {
            context.getSource().sendError(Text.literal("Player not found or has never joined."));
            return 0;
        }

        BigDecimal balance = EconomyManager.getInstance().getBalance(targetUUID);
        context.getSource().sendFeedback(() -> Text.literal(displayName + "'s balance: " + EconomyManager.getInstance().format(balance)), false);
        return 1;
    }

    private static int pay(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity sourcePlayer = context.getSource().getPlayerOrThrow();
        String targetName = StringArgumentType.getString(context, "target");
        double amountDouble = DoubleArgumentType.getDouble(context, "amount");
        BigDecimal amount = BigDecimal.valueOf(amountDouble);

        UUID targetUUID = CommandSupport.getTargetUUID(context, targetName);
        String displayName = CommandSupport.getTargetName(context, targetName);

        if (targetUUID == null) {
            context.getSource().sendError(Text.literal("Player not found or has never joined."));
            return 0;
        }

        if (sourcePlayer.getUuid().equals(targetUUID)) {
            context.getSource().sendError(Text.literal("You cannot pay yourself."));
            return 0;
        }

        if (EconomyManager.getInstance().removeBalance(sourcePlayer.getUuid(), amount)) {
            EconomyManager.getInstance().addBalance(targetUUID, amount);
            String formattedAmount = EconomyManager.getInstance().format(amount);
            CommandSupport.sendCommandFeedback(context, "Paid " + formattedAmount + " to " + displayName, false);

            ServerPlayerEntity target = context.getSource().getServer().getPlayerManager().getPlayer(targetUUID);
            if (target != null) {
                target.sendMessage(Text.literal("Received " + formattedAmount + " from " + sourcePlayer.getName().getString()), false);
            }
            savage.commoneconomy.util.TransactionLogger.log("PAY", sourcePlayer.getName().getString(), displayName, amount, "Payment");
            return 1;
        } else {
            context.getSource().sendError(Text.literal("Insufficient funds."));
            return 0;
        }
    }

    private static int withdraw(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        double amountDouble = DoubleArgumentType.getDouble(context, "amount");
        BigDecimal amount = BigDecimal.valueOf(amountDouble);

        // 1 emerald = $1. Withdraw whole emeralds only.
        BigDecimal whole = amount.setScale(0, java.math.RoundingMode.DOWN);
        if (whole.compareTo(BigDecimal.ONE) < 0) {
            context.getSource().sendError(Text.literal("Withdraw at least 1."));
            return 0;
        }
        if (whole.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
            context.getSource().sendError(Text.literal("That's too many emeralds to withdraw at once (max " + Integer.MAX_VALUE + ")."));
            return 0;
        }
        int emeralds = whole.intValueExact();
        BigDecimal cost = BigDecimal.valueOf(emeralds);

        if (EconomyManager.getInstance().removeBalance(player.getUuid(), cost)) {
            int remaining = emeralds;
            int maxStack = new net.minecraft.item.ItemStack(net.minecraft.item.Items.EMERALD).getMaxCount();
            while (remaining > 0) {
                int give = Math.min(remaining, maxStack);
                player.getInventory().offerOrDrop(new net.minecraft.item.ItemStack(net.minecraft.item.Items.EMERALD, give));
                remaining -= give;
            }
            CommandSupport.sendCommandFeedback(context, "Withdrew " + EconomyManager.getInstance().format(cost) + " as " + emeralds + " emeralds.", false);
            savage.commoneconomy.util.TransactionLogger.log("WITHDRAW", player.getName().getString(), "Emeralds", cost, "Withdrawal");
            return 1;
        } else {
            context.getSource().sendError(Text.literal("Insufficient funds."));
            return 0;
        }
    }
}
