package savage.emeraldeconomy.economy;

import org.junit.jupiter.api.Test;
import savage.emeraldeconomy.config.ItemPrice;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PriceBookTest {

    private PriceBook book() {
        Map<String, ItemPrice> prices = new HashMap<>();
        prices.put("minecraft:diamond", new ItemPrice(new BigDecimal("100"), new BigDecimal("60")));
        prices.put("minecraft:dirt", new ItemPrice(null, new BigDecimal("1")));      // sell-only
        prices.put("minecraft:bedrock", new ItemPrice(new BigDecimal("999"), null)); // buy-only
        return new PriceBook(prices);
    }

    @Test
    void listedItemUsesItsOwnPrices() {
        PriceBook b = book();
        assertEquals(0, b.getBuyPrice("minecraft:diamond").compareTo(new BigDecimal("100")));
        assertEquals(0, b.getSellPrice("minecraft:diamond").compareTo(new BigDecimal("60")));
        assertTrue(b.isBuyable("minecraft:diamond"));
        assertTrue(b.isSellable("minecraft:diamond"));
    }

    @Test
    void unlistedItemHasNoPriceAndIsNotTradeable() {
        PriceBook b = book();
        assertNull(b.getBuyPrice("minecraft:stone"));
        assertNull(b.getSellPrice("minecraft:stone"));
        assertFalse(b.isBuyable("minecraft:stone"));
        assertFalse(b.isSellable("minecraft:stone"));
    }

    @Test
    void sellOnlyItemIsSellableButNotBuyable() {
        PriceBook b = book();
        assertNull(b.getBuyPrice("minecraft:dirt"));
        assertFalse(b.isBuyable("minecraft:dirt"));
        assertEquals(0, b.getSellPrice("minecraft:dirt").compareTo(new BigDecimal("1")));
        assertTrue(b.isSellable("minecraft:dirt"));
    }

    @Test
    void buyOnlyItemIsBuyableButNotSellable() {
        PriceBook b = book();
        assertTrue(b.isBuyable("minecraft:bedrock"));
        assertNull(b.getSellPrice("minecraft:bedrock"));
        assertFalse(b.isSellable("minecraft:bedrock"));
    }

    @Test
    void nullMapIsTreatedAsEmpty() {
        PriceBook b = new PriceBook(null);
        assertNull(b.getBuyPrice("minecraft:dirt"));
        assertFalse(b.isBuyable("minecraft:dirt"));
        assertFalse(b.isSellable("minecraft:dirt"));
    }
}
