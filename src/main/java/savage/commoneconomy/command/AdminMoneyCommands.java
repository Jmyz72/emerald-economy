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

/** Admin balance-management commands: /givemoney, /takemoney, /setmoney, /resetmoney. */
public class AdminMoneyCommands {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("givemoney")
                .requires(source -> savage.commoneconomy.util.PermissionsHelper.check(source, "savscommoneconomy.admin", 2))
                .then(CommandManager.argument("target", StringArgumentType.string())
                        .suggests(CommandSupport.PLAYER_SUGGESTION_PROVIDER)
                        .then(CommandManager.argument("amount", DoubleArgumentType.doubleArg(0))
                                .executes(AdminMoneyCommands::giveMoney))));

        dispatcher.register(CommandManager.literal("takemoney")
                .requires(source -> savage.commoneconomy.util.PermissionsHelper.check(source, "savscommoneconomy.admin", 2))
                .then(CommandManager.argument("target", StringArgumentType.string())
                        .suggests(CommandSupport.PLAYER_SUGGESTION_PROVIDER)
                        .then(CommandManager.argument("amount", DoubleArgumentType.doubleArg(0))
                                .executes(AdminMoneyCommands::takeMoney))));

        dispatcher.register(CommandManager.literal("setmoney")
                .requires(source -> savage.commoneconomy.util.PermissionsHelper.check(source, "savscommoneconomy.admin", 2))
                .then(CommandManager.argument("target", StringArgumentType.string())
                        .suggests(CommandSupport.PLAYER_SUGGESTION_PROVIDER)
                        .then(CommandManager.argument("amount", DoubleArgumentType.doubleArg(0))
                                .executes(AdminMoneyCommands::setMoney))));

        dispatcher.register(CommandManager.literal("resetmoney")
                .requires(source -> savage.commoneconomy.util.PermissionsHelper.check(source, "savscommoneconomy.admin", 2))
                .then(CommandManager.argument("target", StringArgumentType.string())
                        .suggests(CommandSupport.PLAYER_SUGGESTION_PROVIDER)
                        .executes(AdminMoneyCommands::resetMoney)));
    }

    private static int giveMoney(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        String targetName = StringArgumentType.getString(context, "target");
        double amountDouble = DoubleArgumentType.getDouble(context, "amount");
        BigDecimal amount = BigDecimal.valueOf(amountDouble);
        String formattedAmount = EconomyManager.getInstance().format(amount);

        UUID targetUUID = CommandSupport.getTargetUUID(context, targetName);
        String displayName = CommandSupport.getTargetName(context, targetName);

        if (targetUUID == null) {
            context.getSource().sendError(Text.literal("Player not found or has never joined."));
            return 0;
        }

        if (EconomyManager.getInstance().addBalance(targetUUID, amount)) {
            CommandSupport.sendCommandFeedback(context, "Gave " + formattedAmount + " to " + displayName, true);

            ServerPlayerEntity target = context.getSource().getServer().getPlayerManager().getPlayer(targetUUID);
            if (target != null) {
                target.sendMessage(Text.literal("Received " + formattedAmount + " (Admin Gift)"), false);
            }
            savage.commoneconomy.util.TransactionLogger.log("ADMIN_GIVE", context.getSource().getName(), displayName, amount, "Admin Gift");
            return 1;
        } else {
            context.getSource().sendError(Text.literal("Transaction failed. Please try again."));
            return 0;
        }
    }

    private static int takeMoney(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        String targetName = StringArgumentType.getString(context, "target");
        double amountDouble = DoubleArgumentType.getDouble(context, "amount");
        BigDecimal amount = BigDecimal.valueOf(amountDouble);
        String formattedAmount = EconomyManager.getInstance().format(amount);

        UUID targetUUID = CommandSupport.getTargetUUID(context, targetName);
        String displayName = CommandSupport.getTargetName(context, targetName);

        if (targetUUID == null) {
            context.getSource().sendError(Text.literal("Player not found or has never joined."));
            return 0;
        }

        if (!EconomyManager.getInstance().removeBalance(targetUUID, amount)) {
            context.getSource().sendError(Text.literal("Could not take money (Insufficient funds or transaction failed)."));
            return 0;
        } else {
            CommandSupport.sendCommandFeedback(context, "Took " + formattedAmount + " from " + displayName, true);
            savage.commoneconomy.util.TransactionLogger.log("ADMIN_TAKE", context.getSource().getName(), displayName, amount, "Admin Take");
            return 1;
        }
    }

    private static int setMoney(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        String targetName = StringArgumentType.getString(context, "target");
        double amountDouble = DoubleArgumentType.getDouble(context, "amount");
        BigDecimal amount = BigDecimal.valueOf(amountDouble);
        String formattedAmount = EconomyManager.getInstance().format(amount);

        UUID targetUUID = CommandSupport.getTargetUUID(context, targetName);
        String displayName = CommandSupport.getTargetName(context, targetName);

        if (targetUUID == null) {
            context.getSource().sendError(Text.literal("Player not found or has never joined."));
            return 0;
        }

        EconomyManager.getInstance().setBalance(targetUUID, amount);
        CommandSupport.sendCommandFeedback(context, "Set " + displayName + "'s balance to " + formattedAmount, true);

        ServerPlayerEntity target = context.getSource().getServer().getPlayerManager().getPlayer(targetUUID);
        if (target != null) {
            target.sendMessage(Text.literal("Your balance has been set to " + formattedAmount), false);
        }
        savage.commoneconomy.util.TransactionLogger.log("ADMIN_SET", context.getSource().getName(), displayName, amount, "Set Balance");
        return 1;
    }

    private static int resetMoney(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        String targetName = StringArgumentType.getString(context, "target");

        UUID targetUUID = CommandSupport.getTargetUUID(context, targetName);
        String displayName = CommandSupport.getTargetName(context, targetName);

        if (targetUUID == null) {
            context.getSource().sendError(Text.literal("Player not found or has never joined."));
            return 0;
        }

        EconomyManager.getInstance().resetBalance(targetUUID);
        BigDecimal newBalance = EconomyManager.getInstance().getBalance(targetUUID);
        String formattedAmount = EconomyManager.getInstance().format(newBalance);

        CommandSupport.sendCommandFeedback(context, "Reset " + displayName + "'s balance to " + formattedAmount, true);

        ServerPlayerEntity target = context.getSource().getServer().getPlayerManager().getPlayer(targetUUID);
        if (target != null) {
            target.sendMessage(Text.literal("Your balance has been reset to " + formattedAmount), false);
        }
        return 1;
    }
}
