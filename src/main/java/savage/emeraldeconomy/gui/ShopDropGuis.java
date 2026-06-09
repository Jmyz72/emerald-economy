package savage.emeraldeconomy.gui;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the drop-to-sell / drop-to-deposit GUI a player currently has open, so that
 * an abrupt disconnect can still settle it. sgui's {@code onClose} does NOT fire on a
 * hard disconnect, which would otherwise orphan (lose) the items the player dropped in.
 * On disconnect we run the same settle logic so dropped items are sold (credited) or
 * returned — never destroyed.
 */
public final class ShopDropGuis {
    private static final Map<UUID, Runnable> OPEN = new ConcurrentHashMap<>();

    private ShopDropGuis() {}

    /** Record that {@code player} has a settleable drop GUI open. */
    public static void register(UUID player, Runnable settle) {
        OPEN.put(player, settle);
    }

    /** Forget the player's drop GUI (called once it has been settled normally). */
    public static void unregister(UUID player) {
        OPEN.remove(player);
    }

    /** Settle the player's open drop GUI, if any (called on disconnect). Idempotent via the GUI's own guard. */
    public static void settleOnDisconnect(UUID player) {
        Runnable settle = OPEN.remove(player);
        if (settle != null) {
            settle.run();
        }
    }
}
