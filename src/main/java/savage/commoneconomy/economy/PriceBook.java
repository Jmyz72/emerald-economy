package savage.commoneconomy.economy;

import savage.commoneconomy.config.ItemPrice;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Pure pricing logic. An item is tradeable only if worth.json gives it an
 * explicit price: a non-null buy price makes it buyable, a non-null sell price
 * makes it sellable. There is no fallback and no blacklist — unlisted items
 * have no price. Contains no Minecraft references so it can be unit tested.
 */
public class PriceBook {
    private final Map<String, ItemPrice> prices;

    public PriceBook(Map<String, ItemPrice> prices) {
        this.prices = prices != null ? new HashMap<>(prices) : new HashMap<>();
    }

    /** The buy price for an item, or null if it has none (not buyable). */
    public BigDecimal getBuyPrice(String itemId) {
        ItemPrice p = prices.get(itemId);
        return p != null ? p.buy : null;
    }

    /** The sell price for an item, or null if it has none (not sellable). */
    public BigDecimal getSellPrice(String itemId) {
        ItemPrice p = prices.get(itemId);
        return p != null ? p.sell : null;
    }

    /** True if this item has a non-null buy price in worth.json. */
    public boolean isBuyable(String itemId) {
        return getBuyPrice(itemId) != null;
    }

    /** True if this item has a non-null sell price in worth.json. */
    public boolean isSellable(String itemId) {
        return getSellPrice(itemId) != null;
    }
}
