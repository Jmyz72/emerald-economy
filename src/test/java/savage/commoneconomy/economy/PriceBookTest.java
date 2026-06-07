package savage.commoneconomy.economy;

import org.junit.jupiter.api.Test;
import savage.commoneconomy.config.ItemPrice;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PriceBookTest {

    private PriceBook book() {
        Map<String, ItemPrice> prices = new HashMap<>();
        prices.put("minecraft:diamond", new ItemPrice(new BigDecimal("100"), new BigDecimal("60")));
        List<String> unbuyable = List.of("minecraft:bedrock");
        return new PriceBook(prices, unbuyable, new BigDecimal("10"), new BigDecimal("5"));
    }

    @Test
    void listedItemUsesItsOwnPrices() {
        PriceBook b = book();
        assertEquals(0, b.getBuyPrice("minecraft:diamond").compareTo(new BigDecimal("100")));
        assertEquals(0, b.getSellPrice("minecraft:diamond").compareTo(new BigDecimal("60")));
    }

    @Test
    void unlistedItemFallsBackToDefaults() {
        PriceBook b = book();
        assertEquals(0, b.getBuyPrice("minecraft:dirt").compareTo(new BigDecimal("10")));
        assertEquals(0, b.getSellPrice("minecraft:dirt").compareTo(new BigDecimal("5")));
    }

    @Test
    void blacklistedItemIsNotBuyableButStillSellable() {
        PriceBook b = book();
        assertFalse(b.isBuyable("minecraft:bedrock"));
        assertTrue(b.isBuyable("minecraft:diamond"));
        assertTrue(b.isBuyable("minecraft:dirt"));
        assertEquals(0, b.getSellPrice("minecraft:bedrock").compareTo(new BigDecimal("5")));
    }

    @Test
    void nullMapsAreTreatedAsEmpty() {
        PriceBook b = new PriceBook(null, null, new BigDecimal("10"), new BigDecimal("5"));
        assertEquals(0, b.getBuyPrice("minecraft:dirt").compareTo(new BigDecimal("10")));
        assertTrue(b.isBuyable("minecraft:dirt"));
    }
}
