package savage.commoneconomy;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import savage.commoneconomy.command.AdminMoneyCommands;
import savage.commoneconomy.command.BalanceCommands;
import savage.commoneconomy.command.BuyCommand;
import savage.commoneconomy.command.DebugCommands;
import savage.commoneconomy.command.DepositCommand;
import savage.commoneconomy.command.EcoCommands;
import savage.commoneconomy.command.EconomyCommand;
import savage.commoneconomy.command.LogCommand;
import savage.commoneconomy.command.SellCommands;
import savage.commoneconomy.command.ShopCommand;

import java.util.List;

public class SavsCommonEconomy implements ModInitializer {
	public static final String MOD_ID = "savs-common-economy";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("Initializing Savs Common Economy...");

		eu.pb4.common.economy.api.CommonEconomy.register("savs_common_economy",
				savage.commoneconomy.integration.SavsEconomyProvider.INSTANCE);

		LOGGER.info("Savs Common Economy initialized.");

		// Register commands
		List<EconomyCommand> commands = List.of(
				BalanceCommands::register,
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
	}
}
