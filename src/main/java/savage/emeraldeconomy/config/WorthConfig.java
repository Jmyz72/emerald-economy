package savage.emeraldeconomy.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * worth.json: per-item buy/sell prices grouped by creative-tab category.
 * Items not listed have no price and cannot be bought or sold. An item with a
 * buy price can be bought; an item with a sell price can be sold; either may be
 * null ("-").
 *
 * Structure:
 *   categories: { "<tab>": { "<itemId>": { "buy": n, "sell": n } } }
 */
public class WorthConfig {
    public Map<String, Map<String, ItemPrice>> categories = new LinkedHashMap<>();

    /** Legacy flat field from older worth.json files; migrated into categories on load. */
    public Map<String, ItemPrice> itemPrices;

    /** Empty by default; Gson uses this for loading. Use createDefault() for a fresh seed. */
    public WorthConfig() {
    }

    /** A fresh, empty config. Items are added later by /eco generateprices. */
    public static WorthConfig createDefault() {
        return new WorthConfig();
    }

    /**
     * Ensure maps are non-null and migrate any legacy flat {@code itemPrices} into an
     * "uncategorized" category. Returns true if a legacy migration occurred.
     */
    public boolean normalize() {
        if (categories == null) categories = new LinkedHashMap<>();
        // Any legacy "unbuyable" array in old worth.json files is silently ignored by Gson
        // (the field no longer exists); under the whitelist model those items are untradeable
        // unless they carry an explicit price in categories.
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
