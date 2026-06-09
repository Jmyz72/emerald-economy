package savage.emeraldeconomy.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import savage.emeraldeconomy.gui.ShopHubGui;

public class ShopCommand {
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("shop")
                .requires(source -> savage.emeraldeconomy.util.PermissionsHelper.check(source, "emeraldeconomy.command.shop", true))
                .executes(ShopCommand::open));
    }

    private static int open(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        new ShopHubGui(player).open();
        return 1;
    }
}
