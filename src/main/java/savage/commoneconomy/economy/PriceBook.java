package savage.commoneconomy.economy;

import savage.commoneconomy.config.ItemPrice;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Pure pricing logic. Resolves buy/sell prices for any item id, applying a
 * global fallback for unlisted items, and enforces the unbuyable blacklist.
 * Contains no Minecraft references so it can be unit tested directly.
 */
public class PriceBook {
    private final Map<String, ItemPrice> prices;
    private final Set<String> unbuyable;
    private final BigDecimal defaultBuy;
    private final BigDecimal defaultSell;

    public PriceBook(Map<String, ItemPrice> prices,
                     java.util.Collection<String> unbuyable,
                     BigDecimal defaultBuy,
                     BigDecimal defaultSell) {
        this.prices = prices != null ? new HashMap<>(prices) : new HashMap<>();
        this.unbuyable = unbuyable != null ? new HashSet<>(unbuyable) : new HashSet<>();
        this.defaultBuy = defaultBuy;
        this.defaultSell = defaultSell;
    }

    public BigDecimal getBuyPrice(String itemId) {
        ItemPrice p = prices.get(itemId);
        if (p != null && p.buy != null) {
            return p.buy;
        }
        return defaultBuy;
    }

    public BigDecimal getSellPrice(String itemId) {
        ItemPrice p = prices.get(itemId);
        if (p != null && p.sell != null) {
            return p.sell;
        }
        return defaultSell;
    }

    public boolean isBuyable(String itemId) {
        return !unbuyable.contains(itemId);
    }
}
