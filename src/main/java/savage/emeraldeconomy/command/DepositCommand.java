package savage.emeraldeconomy.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import savage.emeraldeconomy.EconomyManager;

import java.math.BigDecimal;

public class DepositCommand {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("deposit")
                .requires(source -> savage.emeraldeconomy.util.PermissionsHelper.check(source, "emeraldeconomy.command.deposit", true))
                .then(CommandManager.literal("all")
                        .executes(ctx -> deposit(ctx, Integer.MAX_VALUE)))
                .then(CommandManager.argument("amount", IntegerArgumentType.integer(1))
                        .executes(ctx -> deposit(ctx, IntegerArgumentType.getInteger(ctx, "amount")))));
    }

    private static int deposit(CommandContext<ServerCommandSource> context, int requested) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();

        int held = 0;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.getItem() == Items.EMERALD) {
                held += stack.getCount();
            }
        }

        if (held <= 0) {
            context.getSource().sendError(Text.literal("You have no emeralds to deposit."));
            return 0;
        }

        int toDeposit = Math.min(requested, held);

        EconomyManager.DepositResult dr = EconomyManager.getInstance().depositEmeralds(player.getUuid(), toDeposit);
        if (!dr.ok()) {
            context.getSource().sendError(Text.literal("Deposit failed, please try again. Your emeralds were not taken."));
            return 0;
        }
        final BigDecimal net = dr.net();
        final BigDecimal fee = dr.fee();
        final int feePercent = dr.feePercent();

        int remaining = toDeposit;
        for (int i = 0; i < player.getInventory().size() && remaining > 0; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.getItem() == Items.EMERALD) {
                int take = Math.min(remaining, stack.getCount());
                stack.decrement(take);
                remaining -= take;
            }
        }

        final int depositedFinal = toDeposit;
        context.getSource().sendFeedback(() -> Text.literal(
                "Deposited " + depositedFinal + " emeralds → " + EconomyManager.getInstance().format(net)
                        + " (fee " + feePercent + "%: " + EconomyManager.getInstance().format(fee) + ")"), false);
        savage.emeraldeconomy.util.TransactionLogger.log("DEPOSIT", player.getName().getString(), "Emeralds", net,
                depositedFinal + " emeralds, " + feePercent + "% fee");
        return 1;
    }
}
