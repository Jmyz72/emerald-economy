package savage.commoneconomy.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import savage.commoneconomy.EconomyManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class EconomyCommands {

    private static final SuggestionProvider<ServerCommandSource> PLAYER_SUGGESTION_PROVIDER = (context, builder) -> {
        List<String> suggestions = new ArrayList<>();
        // Add online players
        suggestions.addAll(context.getSource().getPlayerNames());
        // Add offline players from cache
        suggestions.addAll(EconomyManager.getInstance().getOfflinePlayerNames());
        return CommandSource.suggestMatching(suggestions, builder);
    };

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("bal")
                .requires(source -> savage.commoneconomy.util.PermissionsHelper.check(source, "savscommoneconomy.command.bal", true))
                .executes(EconomyCommands::checkSelfBalance)
                .then(CommandManager.argument("target", StringArgumentType.string())
                        .requires(source -> savage.commoneconomy.util.PermissionsHelper.check(source, "savscommoneconomy.command.bal.others", true))
                        .suggests(PLAYER_SUGGESTION_PROVIDER)
                        .executes(EconomyCommands::checkOtherBalance)));

        dispatcher.register(CommandManager.literal("withdraw")
                .requires(source -> savage.commoneconomy.util.PermissionsHelper.check(source, "savscommoneconomy.command.withdraw", true))
                .then(CommandManager.argument("amount", DoubleArgumentType.doubleArg(1))
                        .executes(EconomyCommands::withdraw)));

        dispatcher.register(CommandManager.literal("givemoney")
                .requires(source -> savage.commoneconomy.util.PermissionsHelper.check(source, "savscommoneconomy.admin", 2))
                .then(CommandManager.argument("target", StringArgumentType.string())
                        .suggests(PLAYER_SUGGESTION_PROVIDER)
                        .then(CommandManager.argument("amount", DoubleArgumentType.doubleArg(0))
                                .executes(EconomyCommands::giveMoney))));

        dispatcher.register(CommandManager.literal("takemoney")
                .requires(source -> savage.commoneconomy.util.PermissionsHelper.check(source, "savscommoneconomy.admin", 2))
                .then(CommandManager.argument("target", StringArgumentType.string())
                        .suggests(PLAYER_SUGGESTION_PROVIDER)
                        .then(CommandManager.argument("amount", DoubleArgumentType.doubleArg(0))
                                .executes(EconomyCommands::takeMoney))));

        dispatcher.register(CommandManager.literal("setmoney")
                .requires(source -> savage.commoneconomy.util.PermissionsHelper.check(source, "savscommoneconomy.admin", 2))
                .then(CommandManager.argument("target", StringArgumentType.string())
                        .suggests(PLAYER_SUGGESTION_PROVIDER)
                        .then(CommandManager.argument("amount", DoubleArgumentType.doubleArg(0))
                                .executes(EconomyCommands::setMoney))));

        dispatcher.register(CommandManager.literal("resetmoney")
                .requires(source -> savage.commoneconomy.util.PermissionsHelper.check(source, "savscommoneconomy.admin", 2))
                .then(CommandManager.argument("target", StringArgumentType.string())
                        .suggests(PLAYER_SUGGESTION_PROVIDER)
                        .executes(EconomyCommands::resetMoney)));

        dispatcher.register(CommandManager.literal("baltop")
                .requires(source -> savage.commoneconomy.util.PermissionsHelper.check(source, "savscommoneconomy.command.baltop", true))
                .executes(EconomyCommands::balTop));
        dispatcher.register(CommandManager.literal("balancetop")
                .requires(source -> savage.commoneconomy.util.PermissionsHelper.check(source, "savscommoneconomy.command.baltop", true))
                .executes(EconomyCommands::balTop));

        dispatcher.register(CommandManager.literal("pay")
                .requires(source -> savage.commoneconomy.util.PermissionsHelper.check(source, "savscommoneconomy.command.pay", true))
                .then(CommandManager.argument("target", StringArgumentType.string())
                        .suggests(PLAYER_SUGGESTION_PROVIDER)
                        .then(CommandManager.argument("amount", DoubleArgumentType.doubleArg(0))
                                .executes(EconomyCommands::pay))));

        dispatcher.register(CommandManager.literal("balance")
                .requires(source -> savage.commoneconomy.util.PermissionsHelper.check(source, "savscommoneconomy.command.bal", true))
                .executes(EconomyCommands::checkSelfBalance)
                .then(CommandManager.argument("target", StringArgumentType.string())
                        .requires(source -> savage.commoneconomy.util.PermissionsHelper.check(source, "savscommoneconomy.command.bal.others", true))
                        .suggests(PLAYER_SUGGESTION_PROVIDER)
                        .executes(EconomyCommands::checkOtherBalance)));
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

    private static java.util.UUID getTargetUUID(CommandContext<ServerCommandSource> context, String targetName) throws CommandSyntaxException {
        if (targetName.equals("@s")) {
            return context.getSource().getPlayerOrThrow().getUuid();
        }
        
        ServerPlayerEntity target = context.getSource().getServer().getPlayerManager().getPlayer(targetName);
        if (target != null) {
            return target.getUuid();
        }
        return EconomyManager.getInstance().getUUID(targetName);
    }

    private static String getTargetName(CommandContext<ServerCommandSource> context, String targetName) throws CommandSyntaxException {
        if (targetName.equals("@s")) {
            return context.getSource().getPlayerOrThrow().getName().getString();
        }

        ServerPlayerEntity target = context.getSource().getServer().getPlayerManager().getPlayer(targetName);
        if (target != null) {
            return target.getName().getString();
        }
        return targetName;
    }

    private static int checkSelfBalance(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        BigDecimal balance = EconomyManager.getInstance().getBalance(player.getUuid());
        context.getSource().sendFeedback(() -> Text.literal("Your balance: " + EconomyManager.getInstance().format(balance)), false);
        return 1;
    }

    private static int checkOtherBalance(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        String targetName = StringArgumentType.getString(context, "target");
        UUID targetUUID = getTargetUUID(context, targetName);
        String displayName = getTargetName(context, targetName);

        if (targetUUID == null) {
            context.getSource().sendError(Text.literal("Player not found or has never joined."));
            return 0;
        }

        BigDecimal balance = EconomyManager.getInstance().getBalance(targetUUID);
        context.getSource().sendFeedback(() -> Text.literal(displayName + "'s balance: " + EconomyManager.getInstance().format(balance)), false);
        return 1;
    }

    private static void sendCommandFeedback(CommandContext<ServerCommandSource> context, String message, boolean broadcastToOps) {
        context.getSource().sendFeedback(() -> Text.literal(message), broadcastToOps);
    }

    private static int pay(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity sourcePlayer = context.getSource().getPlayerOrThrow();
        String targetName = StringArgumentType.getString(context, "target");
        double amountDouble = DoubleArgumentType.getDouble(context, "amount");
        BigDecimal amount = BigDecimal.valueOf(amountDouble);

        UUID targetUUID = getTargetUUID(context, targetName);
        String displayName = getTargetName(context, targetName);

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
            sendCommandFeedback(context, "Paid " + formattedAmount + " to " + displayName, false);
            
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

    private static int giveMoney(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        String targetName = StringArgumentType.getString(context, "target");
        double amountDouble = DoubleArgumentType.getDouble(context, "amount");
        BigDecimal amount = BigDecimal.valueOf(amountDouble);
        String formattedAmount = EconomyManager.getInstance().format(amount);

        UUID targetUUID = getTargetUUID(context, targetName);
        String displayName = getTargetName(context, targetName);

        if (targetUUID == null) {
            context.getSource().sendError(Text.literal("Player not found or has never joined."));
            return 0;
        }

        if (EconomyManager.getInstance().addBalance(targetUUID, amount)) {
            sendCommandFeedback(context, "Gave " + formattedAmount + " to " + displayName, true);
            
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

        UUID targetUUID = getTargetUUID(context, targetName);
        String displayName = getTargetName(context, targetName);

        if (targetUUID == null) {
            context.getSource().sendError(Text.literal("Player not found or has never joined."));
            return 0;
        }

        if (!EconomyManager.getInstance().removeBalance(targetUUID, amount)) {
            context.getSource().sendError(Text.literal("Could not take money (Insufficient funds or transaction failed)."));
            return 0;
        } else {
            sendCommandFeedback(context, "Took " + formattedAmount + " from " + displayName, true);
            savage.commoneconomy.util.TransactionLogger.log("ADMIN_TAKE", context.getSource().getName(), displayName, amount, "Admin Take");
            return 1;
        }
    }

    private static int setMoney(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        String targetName = StringArgumentType.getString(context, "target");
        double amountDouble = DoubleArgumentType.getDouble(context, "amount");
        BigDecimal amount = BigDecimal.valueOf(amountDouble);
        String formattedAmount = EconomyManager.getInstance().format(amount);

        UUID targetUUID = getTargetUUID(context, targetName);
        String displayName = getTargetName(context, targetName);

        if (targetUUID == null) {
            context.getSource().sendError(Text.literal("Player not found or has never joined."));
            return 0;
        }

        EconomyManager.getInstance().setBalance(targetUUID, amount);
        sendCommandFeedback(context, "Set " + displayName + "'s balance to " + formattedAmount, true);
        
        ServerPlayerEntity target = context.getSource().getServer().getPlayerManager().getPlayer(targetUUID);
        if (target != null) {
            target.sendMessage(Text.literal("Your balance has been set to " + formattedAmount), false);
        }
        savage.commoneconomy.util.TransactionLogger.log("ADMIN_SET", context.getSource().getName(), displayName, amount, "Set Balance");
        return 1;
    }

    private static int resetMoney(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        String targetName = StringArgumentType.getString(context, "target");

        UUID targetUUID = getTargetUUID(context, targetName);
        String displayName = getTargetName(context, targetName);

        if (targetUUID == null) {
            context.getSource().sendError(Text.literal("Player not found or has never joined."));
            return 0;
        }

        EconomyManager.getInstance().resetBalance(targetUUID);
        BigDecimal newBalance = EconomyManager.getInstance().getBalance(targetUUID);
        String formattedAmount = EconomyManager.getInstance().format(newBalance);

        sendCommandFeedback(context, "Reset " + displayName + "'s balance to " + formattedAmount, true);
        
        ServerPlayerEntity target = context.getSource().getServer().getPlayerManager().getPlayer(targetUUID);
        if (target != null) {
            target.sendMessage(Text.literal("Your balance has been reset to " + formattedAmount), false);
        }
        return 1;
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
            sendCommandFeedback(context, "Withdrew " + EconomyManager.getInstance().format(cost) + " as " + emeralds + " emeralds.", false);
            savage.commoneconomy.util.TransactionLogger.log("WITHDRAW", player.getName().getString(), "Emeralds", cost, "Withdrawal");
            return 1;
        } else {
            context.getSource().sendError(Text.literal("Insufficient funds."));
            return 0;
        }
    }
}

