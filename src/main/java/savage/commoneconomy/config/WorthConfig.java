package savage.commoneconomy.config;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * worth.json: per-item buy/sell prices plus the unbuyable blacklist.
 * Items not present in itemPrices use the default buy/sell prices from
 * EconomyConfig. Items in unbuyable cannot be bought (but can still be sold).
 */
public class WorthConfig {
    public Map<String, ItemPrice> itemPrices = new LinkedHashMap<>();
    public List<String> unbuyable = new ArrayList<>();

    public WorthConfig() {
        // A few curated examples; admins edit worth.json to expand this.
        itemPrices.put("minecraft:diamond", new ItemPrice(BigDecimal.valueOf(100), BigDecimal.valueOf(60)));
        itemPrices.put("minecraft:iron_ingot", new ItemPrice(BigDecimal.valueOf(20), BigDecimal.valueOf(12)));
        itemPrices.put("minecraft:apple", new ItemPrice(BigDecimal.valueOf(10), BigDecimal.valueOf(5)));

        // Items that must never be purchasable (still sellable).
        unbuyable.add("minecraft:bedrock");
        unbuyable.add("minecraft:spawner");
        unbuyable.add("minecraft:command_block");
        unbuyable.add("minecraft:chain_command_block");
        unbuyable.add("minecraft:repeating_command_block");
        unbuyable.add("minecraft:barrier");
        unbuyable.add("minecraft:light");
        unbuyable.add("minecraft:structure_block");
        unbuyable.add("minecraft:structure_void");
        unbuyable.add("minecraft:jigsaw");
        unbuyable.add("minecraft:end_portal_frame");
    }
}
