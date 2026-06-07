package savage.commoneconomy.shop;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks pending shop transactions waiting for player input
 */
public class ShopTransactionManager {
    private static ShopTransactionManager instance;
    private final Map<UUID, PendingTransaction> pendingTransactions = new HashMap<>();
    
    private ShopTransactionManager() {}
    
    public static ShopTransactionManager getInstance() {
        if (instance == null) {
            instance = new ShopTransactionManager();
        }
        return instance;
    }
    
    public void startTransaction(UUID playerId, BlockPos shopPos, boolean isBuying) {
        pendingTransactions.put(playerId, new PendingTransaction(shopPos, isBuying));
    }
    
    public PendingTransaction getPendingTransaction(UUID playerId) {
        return pendingTransactions.get(playerId);
    }
    
    public void clearTransaction(UUID playerId) {
        pendingTransactions.remove(playerId);
    }
    
    public boolean hasPendingTransaction(UUID playerId) {
        return pendingTransactions.containsKey(playerId);
    }
    
    public static class PendingTransaction {
        private final BlockPos shopPos;
        private final boolean isBuying;
        private final long timestamp;
        
        public PendingTransaction(BlockPos shopPos, boolean isBuying) {
            this.shopPos = shopPos;
            this.isBuying = isBuying;
            this.timestamp = System.currentTimeMillis();
        }
        
        public BlockPos getShopPos() { return shopPos; }
        public boolean isBuying() { return isBuying; }
        public long getTimestamp() { return timestamp; }
        
        public boolean isExpired() {
            return System.currentTimeMillis() - timestamp > 30000; // 30 second timeout
        }
    }
}
