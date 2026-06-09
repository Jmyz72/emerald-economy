package savage.emeraldeconomy.util;

import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.command.permission.PermissionPredicate;
import net.minecraft.server.command.ServerCommandSource;

public class PermissionsHelper {

    private static boolean permissionsApiAvailable;

    // Vanilla op-level check. MC 1.21.11 removed hasPermissionLevel(int) in favour of
    // the data-driven permission system, so check a Permission.Level against the predicate.
    private static boolean hasVanillaLevel(PermissionPredicate permissions, int level) {
        return permissions.hasPermission(new Permission.Level(PermissionLevel.fromLevel(level)));
    }

    static {
        try {
            Class.forName("me.lucko.fabric.api.permissions.v0.Permissions");
            permissionsApiAvailable = true;
            savage.emeraldeconomy.EmeraldEconomy.LOGGER.info("Fabric Permissions API found. Using granular permissions.");
        } catch (ClassNotFoundException e) {
            permissionsApiAvailable = false;
            savage.emeraldeconomy.EmeraldEconomy.LOGGER.warn("Fabric Permissions API NOT found. Falling back to vanilla OP levels.");
        }
    }

    public static boolean check(ServerCommandSource source, String node, int level) {
        if (permissionsApiAvailable) {
            try {
                return me.lucko.fabric.api.permissions.v0.Permissions.check(source, node, level);
            } catch (Throwable t) {
                savage.emeraldeconomy.EmeraldEconomy.LOGGER.error("Error checking permissions for node " + node + ". Falling back to vanilla check.", t);
                return hasVanillaLevel(source.getPermissions(), level);
            }
        }
        return hasVanillaLevel(source.getPermissions(), level);
    }

    public static boolean check(ServerCommandSource source, String node, boolean fallback) {
        if (permissionsApiAvailable) {
            try {
                return me.lucko.fabric.api.permissions.v0.Permissions.check(source, node, fallback);
            } catch (Throwable t) {
                savage.emeraldeconomy.EmeraldEconomy.LOGGER.error("Error checking permissions for node " + node + ". Falling back to default.", t);
                return fallback;
            }
        }
        return fallback;
    }

    public static boolean check(net.minecraft.server.network.ServerPlayerEntity player, String node, int level) {
        if (permissionsApiAvailable) {
            try {
                return me.lucko.fabric.api.permissions.v0.Permissions.check(player, node, level);
            } catch (Throwable t) {
                savage.emeraldeconomy.EmeraldEconomy.LOGGER.error("Error checking permissions for node " + node + ". Falling back to vanilla check.", t);
                return hasVanillaLevel(player.getPermissions(), level);
            }
        }
        return hasVanillaLevel(player.getPermissions(), level);
    }

    public static boolean check(net.minecraft.server.network.ServerPlayerEntity player, String node, boolean fallback) {
        if (permissionsApiAvailable) {
            try {
                return me.lucko.fabric.api.permissions.v0.Permissions.check(player, node, fallback);
            } catch (Throwable t) {
                savage.emeraldeconomy.EmeraldEconomy.LOGGER.error("Error checking permissions for node " + node + ". Falling back to default.", t);
                return fallback;
            }
        }
        return fallback;
    }
}
