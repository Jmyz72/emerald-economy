package savage.emeraldeconomy;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import savage.emeraldeconomy.command.AdminMoneyCommands;
import savage.emeraldeconomy.command.BalanceCommands;
import savage.emeraldeconomy.command.BuyCommand;
import savage.emeraldeconomy.command.DailyCommand;
import savage.emeraldeconomy.command.DebugCommands;
import savage.emeraldeconomy.command.DepositCommand;
import savage.emeraldeconomy.command.EcoCommands;
import savage.emeraldeconomy.command.EconomyCommand;
import savage.emeraldeconomy.command.LogCommand;
import savage.emeraldeconomy.command.SellCommands;
import savage.emeraldeconomy.command.ShopCommand;

import java.util.List;

public class EmeraldEconomy implements ModInitializer {
	public static final String MOD_ID = "emerald-economy";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("Initializing Emerald Economy...");

		eu.pb4.common.economy.api.CommonEconomy.register("emerald_economy",
				savage.emeraldeconomy.integration.EmeraldEconomyProvider.INSTANCE);

		LOGGER.info("Emerald Economy initialized.");

		// Register commands
		List<EconomyCommand> commands = List.of(
				BalanceCommands::register,
				DailyCommand::register,
				AdminMoneyCommands::register,
				SellCommands::register,
				BuyCommand::register,
				DepositCommand::register,
				LogCommand::register,
				DebugCommands::register,
				EcoCommands::register,
				ShopCommand::register);
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				commands.forEach(command -> command.register(dispatcher)));

		// Load economy data when server starts
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			EconomyManager.getInstance().initStorage();
			EconomyManager.getInstance().setServer(server);
			EconomyManager.getInstance().load();
			savage.emeraldeconomy.rewards.DailyRewards.getInstance().load();
		});

		// Save economy data when server stops
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			EconomyManager.getInstance().save();
		});

		// Create account on join
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (!EconomyManager.getInstance().hasAccount(handler.player.getUuid())) {
				EconomyManager.getInstance().createAccount(handler.player.getUuid(),
						handler.player.getName().getString());
			}
		});

		// Settle any open drop-to-sell / deposit GUI on disconnect so dropped items are
		// never lost (sgui's onClose does not fire on an abrupt disconnect).
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
				savage.emeraldeconomy.gui.ShopDropGuis.settleOnDisconnect(handler.player.getUuid()));
	}
}
