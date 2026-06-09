package savage.emeraldeconomy.config;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

public class EconomyConfig {
    public BigDecimal defaultBalance = BigDecimal.valueOf(25000);
    public String currencySymbol = "$";

    // Percent of deposited emerald value burned as a fee (0-100). 20 = keep 80%.
    public int depositFeePercent = 20;

    public StorageConfig storage = new StorageConfig();

    public RewardsConfig rewards = new RewardsConfig();

    /**
     * SQLite-only storage settings. The database file name is fixed inside
     * savdbcore ("economy_data.sqlite"); these fields tune table naming and
     * the connection pool.
     */
    public static class StorageConfig {
        public String tablePrefix = "emerald_eco_";
        public int poolSize = 10;
        public long connectionTimeout = 30000;
        public long idleTimeout = 600000;
    }

    /**
     * New-player income knobs. All live-tunable via /eco reload. Money fields are
     * BigDecimal (decimals allowed); dailyCooldownHours is a whole-hour count.
     * mobKillRewards is an optional per-entity-id override map (the "tier list"):
     * a listed mob pays its amount; anything else falls back to the flat
     * hostile/passive rate.
     */
    public static class RewardsConfig {
        public BigDecimal dailyAmount = BigDecimal.valueOf(10000);
        public int dailyCooldownHours = 12;
        public BigDecimal mobKillHostile = BigDecimal.valueOf(100);
        public BigDecimal mobKillPassive = BigDecimal.valueOf(50);
        public Map<String, BigDecimal> mobKillRewards = defaultMobKillRewards();
        public BigDecimal playtimePerMinute = BigDecimal.valueOf(50);

        private static Map<String, BigDecimal> defaultMobKillRewards() {
            Map<String, BigDecimal> m = new LinkedHashMap<>();
            m.put("minecraft:ender_dragon", BigDecimal.valueOf(50000));
            m.put("minecraft:wither", BigDecimal.valueOf(25000));
            m.put("minecraft:warden", BigDecimal.valueOf(10000));
            m.put("minecraft:elder_guardian", BigDecimal.valueOf(2000));
            m.put("minecraft:blaze", BigDecimal.valueOf(250));
            return m;
        }
    }
}
