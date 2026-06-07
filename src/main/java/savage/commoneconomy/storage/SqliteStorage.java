package savage.commoneconomy.storage;

import savage.savdbcore.config.DBCoreConfig;

/**
 * SQLite storage for economy data.
 * Delegates connection management to savdbcore's SqliteStorage.
 */
public class SqliteStorage extends SqlStorage {

    public SqliteStorage(savage.commoneconomy.EconomyManager manager, String tablePrefix) {
        super(manager, tablePrefix);
        
        // Convert economy config to DBCore config
        DBCoreConfig.StorageConfig coreConfig = new DBCoreConfig.StorageConfig();
        coreConfig.poolSize = manager.getConfig().storage.poolSize;
        coreConfig.connectionTimeout = manager.getConfig().storage.connectionTimeout;
        coreConfig.idleTimeout = manager.getConfig().storage.idleTimeout;
        
        // Create the underlying storage
        savage.savdbcore.storage.SqliteStorage dbStorage = new savage.savdbcore.storage.SqliteStorage(
            "savs-common-economy",
            "economy_data.sqlite",
            tablePrefix,
            coreConfig
        );
        dbStorage.initialize();
        
        // Copy the dataSource reference
        this.dataSource = dbStorage.dataSource;
    }

    @Override
    protected void setupDataSource() {
        // Already set up in constructor
    }

    @Override
    protected String getTransactionsTableCreationSql() {
        return "CREATE TABLE IF NOT EXISTS " + tablePrefix + "transactions (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "timestamp BIGINT NOT NULL, " +
                "source VARCHAR(16) NOT NULL, " +
                "target VARCHAR(16) NOT NULL, " +
                "amount DECIMAL(20, 2) NOT NULL, " +
                "type VARCHAR(16) NOT NULL, " +
                "details VARCHAR(255)" +
                ")";
    }
}
