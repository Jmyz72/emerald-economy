package savage.commoneconomy.shop;

import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;

import java.math.BigDecimal;
import java.util.UUID;

public class Shop {
    private final UUID shopId;
    private final String worldId;
    private final BlockPos chestLocation;
    private final UUID ownerId;
    private String ownerName;
    private ShopType type;
    private ItemStack item;
    private BigDecimal price;
    private boolean buying;
    private int stock;

    public Shop(UUID shopId, String worldId, BlockPos chestLocation, UUID ownerId, String ownerName, 
                ShopType type, ItemStack item, BigDecimal price, boolean buying, int stock) {
        this.shopId = shopId;
        this.worldId = worldId;
        this.chestLocation = chestLocation;
        this.ownerId = ownerId;
        this.ownerName = ownerName;
        this.type = type;
        this.item = item.copy();
        this.price = price;
        this.buying = buying;
        this.stock = stock;
    }

    public UUID getShopId() { return shopId; }
    public String getWorldId() { return worldId; }
    public BlockPos getChestLocation() { return chestLocation; }
    public UUID getOwnerId() { return ownerId; }
    public String getOwnerName() { return ownerName; }
    public ShopType getType() { return type; }
    public ItemStack getItem() { return item.copy(); }
    public BigDecimal getPrice() { return price; }
    public boolean isBuying() { return buying; }
    public int getStock() { return stock; }

    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }
    public void setType(ShopType type) { this.type = type; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public void setStock(int stock) { this.stock = stock; }

    public boolean canSell(int amount) {
        if (type == ShopType.ADMIN) return true;
        return stock >= amount;
    }

    public void addStock(int amount) {
        if (type != ShopType.ADMIN) {
            this.stock += amount;
        }
    }

    public void removeStock(int amount) {
        if (type != ShopType.ADMIN) {
            this.stock -= amount;
            if (this.stock < 0) this.stock = 0;
        }
    }

    public boolean isAdmin() {
        return type == ShopType.ADMIN;
    }
}
