package savage.commoneconomy.storage;

import savage.savdbcore.config.DBCoreConfig;

/**
 * MySQL storage for economy data.
 * Delegates connection management to savdbcore's MysqlStorage.
 */
public class MysqlStorage extends SqlStorage {

    public MysqlStorage(savage.commoneconomy.EconomyManager manager, String host, int port, String database, String user, String password, String tablePrefix) {
        super(manager, tablePrefix);
        
        // Convert economy config to DBCore config
        DBCoreConfig.StorageConfig coreConfig = new DBCoreConfig.StorageConfig();
        coreConfig.poolSize = manager.getConfig().storage.poolSize;
        coreConfig.connectionTimeout = manager.getConfig().storage.connectionTimeout;
        coreConfig.idleTimeout = manager.getConfig().storage.idleTimeout;
        
        // Create the underlying storage
        savage.savdbcore.storage.MysqlStorage dbStorage = new savage.savdbcore.storage.MysqlStorage(
            host, port, database, user, password, tablePrefix, coreConfig
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
                "id INT AUTO_INCREMENT PRIMARY KEY, " +
                "timestamp BIGINT NOT NULL, " +
                "source VARCHAR(255) NOT NULL, " +
                "target VARCHAR(255) NOT NULL, " +
                "amount DECIMAL(20, 2) NOT NULL, " +
                "type VARCHAR(32) NOT NULL, " +
                "details TEXT" +
                ")";
    }
}
