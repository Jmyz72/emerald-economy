package savage.commoneconomy.config;

import java.math.BigDecimal;

/**
 * A buy/sell price pair for a single item, as stored in worth.json.
 * buy  = price a player pays to /buy one unit
 * sell = price a player receives to /sell one unit
 */
public class ItemPrice {
    public BigDecimal buy;
    public BigDecimal sell;

    public ItemPrice() {
    }

    public ItemPrice(BigDecimal buy, BigDecimal sell) {
        this.buy = buy;
        this.sell = sell;
    }
}
