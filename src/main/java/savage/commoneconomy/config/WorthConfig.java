package savage.commoneconomy.config;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * worth.json: per-item buy/sell prices grouped by creative-tab category, plus
 * the unbuyable blacklist. Items not listed use the default buy/sell prices
 * from EconomyConfig. Items in unbuyable cannot be bought (but can still be sold).
 *
 * Structure:
 *   categories: { "<tab>": { "<itemId>": { "buy": n, "sell": n } } }
 *   unbuyable:  [ "<itemId>", ... ]
 */
public class WorthConfig {
    public Map<String, Map<String, ItemPrice>> categories = new LinkedHashMap<>();
    public List<String> unbuyable = new ArrayList<>();

    /** Legacy flat field from older worth.json files; migrated into categories on load. */
    public Map<String, ItemPrice> itemPrices;

    /** Empty by default; Gson uses this for loading. Use createDefault() for a fresh seed. */
    public WorthConfig() {
    }

    /** A fresh config seeded with a few example prices and the unbuyable defaults. */
    public static WorthConfig createDefault() {
        WorthConfig c = new WorthConfig();

        Map<String, ItemPrice> ingredients = new LinkedHashMap<>();
        ingredients.put("minecraft:diamond", new ItemPrice(BigDecimal.valueOf(100), BigDecimal.valueOf(60)));
        ingredients.put("minecraft:iron_ingot", new ItemPrice(BigDecimal.valueOf(20), BigDecimal.valueOf(12)));
        c.categories.put("ingredients", ingredients);

        Map<String, ItemPrice> food = new LinkedHashMap<>();
        food.put("minecraft:apple", new ItemPrice(BigDecimal.valueOf(10), BigDecimal.valueOf(5)));
        c.categories.put("food_and_drink", food);

        c.unbuyable.add("minecraft:bedrock");
        c.unbuyable.add("minecraft:spawner");
        c.unbuyable.add("minecraft:command_block");
        c.unbuyable.add("minecraft:chain_command_block");
        c.unbuyable.add("minecraft:repeating_command_block");
        c.unbuyable.add("minecraft:barrier");
        c.unbuyable.add("minecraft:light");
        c.unbuyable.add("minecraft:structure_block");
        c.unbuyable.add("minecraft:structure_void");
        c.unbuyable.add("minecraft:jigsaw");
        c.unbuyable.add("minecraft:end_portal_frame");
        return c;
    }

    /**
     * Ensure maps are non-null and migrate any legacy flat {@code itemPrices} into an
     * "uncategorized" category. Returns true if a legacy migration occurred.
     */
    public boolean normalize() {
        if (categories == null) categories = new LinkedHashMap<>();
        if (unbuyable == null) unbuyable = new ArrayList<>();
        boolean migrated = false;
        if (itemPrices != null && !itemPrices.isEmpty()) {
            categories.computeIfAbsent("uncategorized", k -> new LinkedHashMap<>()).putAll(itemPrices);
            migrated = true;
        }
        itemPrices = null; // drop legacy so it is not re-serialized
        return migrated;
    }

    /** Flatten all category maps into a single id -> price lookup. */
    public Map<String, ItemPrice> flatten() {
        Map<String, ItemPrice> flat = new LinkedHashMap<>();
        if (categories != null) {
            for (Map<String, ItemPrice> cat : categories.values()) {
                if (cat != null) flat.putAll(cat);
            }
        }
        return flat;
    }

    /** True if the item id already has a price in any category. */
    public boolean contains(String itemId) {
        if (categories == null) return false;
        for (Map<String, ItemPrice> cat : categories.values()) {
            if (cat != null && cat.containsKey(itemId)) return true;
        }
        return false;
    }
}
