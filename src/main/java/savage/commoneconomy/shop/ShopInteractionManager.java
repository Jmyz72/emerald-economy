package savage.commoneconomy.shop;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ShopInteractionManager {
    private static ShopInteractionManager instance;
    private final Map<UUID, PendingInteraction> pendingInteractions = new HashMap<>();

    private ShopInteractionManager() {}

    public static ShopInteractionManager getInstance() {
        if (instance == null) {
            instance = new ShopInteractionManager();
        }
        return instance;
    }

    public void addPendingInteraction(UUID playerId, Shop shop, boolean isBuying) {
        pendingInteractions.put(playerId, new PendingInteraction(shop, isBuying));
    }

    public PendingInteraction getPendingInteraction(UUID playerId) {
        return pendingInteractions.get(playerId);
    }

    public void removePendingInteraction(UUID playerId) {
        pendingInteractions.remove(playerId);
    }

    public static class PendingInteraction {
        private final Shop shop;
        private final boolean isBuying; // true if player is BUYING from shop, false if SELLING to shop
        private final long timestamp;

        public PendingInteraction(Shop shop, boolean isBuying) {
            this.shop = shop;
            this.isBuying = isBuying;
            this.timestamp = System.currentTimeMillis();
        }

        public Shop getShop() { return shop; }
        public boolean isBuying() { return isBuying; }
        public boolean isExpired() {
            return System.currentTimeMillis() - timestamp > 30000; // 30 seconds expiry
        }
    }
}
