package savage.emeraldeconomy.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import savage.emeraldeconomy.EconomyManager;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin price-management commands:
 *   /eco generateprices  - populate worth.json with every registered item at the
 *                          flat default price, grouped by creative tab (merge-safe).
 *   /eco reload          - reload worth.json from disk after hand-editing.
 */
public class EcoCommands {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("eco")
                .requires(source -> savage.emeraldeconomy.util.PermissionsHelper.check(source, "emeraldeconomy.admin", 2))
                .then(CommandManager.literal("generateprices")
                        .executes(EcoCommands::generatePrices))
                .then(CommandManager.literal("reload")
                        .executes(EcoCommands::reload)));
    }

    private static int generatePrices(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Map<String, String> idToCategory = new LinkedHashMap<>();

        // Resolve each item's creative tab. Display stacks must be populated first;
        // on a dedicated server this is otherwise lazy, so force it here.
        try {
            ServerWorld world = source.getServer().getOverworld();
            ItemGroups.updateDisplayContext(world.getEnabledFeatures(), true,
                    source.getServer().getRegistryManager());

            for (ItemGroup group : Registries.ITEM_GROUP) {
                if (group.getType() != ItemGroup.Type.CATEGORY) {
                    continue;
                }
                Identifier gid = Registries.ITEM_GROUP.getId(group);
                if (gid == null) {
                    continue;
                }
                String category = categoryName(gid);
                try {
                    for (ItemStack stack : group.getDisplayStacks()) {
                        Identifier itemId = Registries.ITEM.getId(stack.getItem());
                        idToCategory.putIfAbsent(itemId.toString(), category);
                    }
                } catch (Exception ignored) {
                    // Group not populated for this context; its items fall through to uncategorized.
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            source.sendError(Text.literal("Could not read creative tabs; items will be uncategorized."));
        }

        // Catch-all: every registered item not placed in a tab goes to "uncategorized".
        for (Identifier itemId : Registries.ITEM.getIds()) {
            idToCategory.putIfAbsent(itemId.toString(), "uncategorized");
        }

        int added = EconomyManager.getInstance().generatePrices(idToCategory);
        int total = EconomyManager.getInstance().getAllItemPrices().size();
        source.sendFeedback(() -> Text.literal("Generated prices: added " + added
                + " new item(s), " + total + " priced total. Old file backed up to worth.json.bak. "
                + "Edit worth.json, then /eco reload."), true);
        return 1;
    }

    private static int reload(CommandContext<ServerCommandSource> context) {
        EconomyManager.getInstance().reloadConfig();
        EconomyManager.getInstance().reloadPrices();
        int total = EconomyManager.getInstance().getAllItemPrices().size();
        context.getSource().sendFeedback(() -> Text.literal("Reloaded config.json and worth.json ("
                + total + " priced items)."), true);
        return 1;
    }

    /** Category name from a tab id: vanilla uses the bare path, mods use "namespace.path". */
    private static String categoryName(Identifier groupId) {
        if ("minecraft".equals(groupId.getNamespace())) {
            return groupId.getPath();
        }
        return groupId.getNamespace() + "." + groupId.getPath();
    }
}
