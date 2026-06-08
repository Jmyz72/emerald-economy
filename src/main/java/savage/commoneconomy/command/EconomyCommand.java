package savage.commoneconomy.command;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.server.command.ServerCommandSource;

/** A registrable group of economy commands. */
@FunctionalInterface
public interface EconomyCommand {
    void register(CommandDispatcher<ServerCommandSource> dispatcher);
}
