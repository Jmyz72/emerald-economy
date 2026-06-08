package savage.commoneconomy.config;

import java.math.BigDecimal;

public class EconomyConfig {
    public BigDecimal defaultBalance = BigDecimal.valueOf(1000);
    public String currencySymbol = "$";

    // Percent of deposited emerald value burned as a fee (0-100). 20 = keep 80%.
    public int depositFeePercent = 20;

    public StorageConfig storage = new StorageConfig();

    /**
     * SQLite-only storage settings. The database file name is fixed inside
     * savdbcore ("economy_data.sqlite"); these fields tune table naming and
     * the connection pool.
     */
    public static class StorageConfig {
        public String tablePrefix = "savs_eco_";
        public int poolSize = 10;
        public long connectionTimeout = 30000;
        public long idleTimeout = 600000;
    }
}
