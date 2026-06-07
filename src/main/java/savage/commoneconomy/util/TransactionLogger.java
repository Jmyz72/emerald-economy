package savage.commoneconomy.util;

import net.fabricmc.loader.api.FabricLoader;
import savage.commoneconomy.SavsCommonEconomy;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class TransactionLogger {

    private static final File LOG_FILE = FabricLoader.getInstance().getGameDir().resolve("logs/economy.log").toFile();
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final java.util.concurrent.ExecutorService EXECUTOR = java.util.concurrent.Executors.newSingleThreadExecutor();

    public static void log(String type, String source, String target, BigDecimal amount, String details) {
        EXECUTOR.submit(() -> {
            long timestamp = System.currentTimeMillis();
            
            // Try to log to database if available
            savage.commoneconomy.storage.EconomyStorage storage = savage.commoneconomy.EconomyManager.getStorage();
            if (storage instanceof savage.commoneconomy.storage.SqlStorage) {
                storage.logTransaction(timestamp, source, target, amount, type, details);
                return;
            }

            // Fallback to file logging
            LogEntry entry = new LogEntry(LocalDateTime.now(), type, source, target, amount, details);
            String logLine = formatLogEntry(entry);

            try (FileWriter fw = new FileWriter(LOG_FILE, true);
                 BufferedWriter bw = new BufferedWriter(fw);
                 PrintWriter out = new PrintWriter(bw)) {
                out.println(logLine);
            } catch (IOException e) {
                SavsCommonEconomy.LOGGER.error("Failed to write to economy log", e);
            }
        });
    }

    private static String formatLogEntry(LogEntry entry) {
        return String.format("[%s] [%s] %s -> %s: $%s (%s)", 
            entry.timestamp.format(DATE_FORMAT), entry.type, entry.source, entry.target, entry.amount.toPlainString(), entry.details);
    }

    public static java.util.List<LogEntry> searchLogs(String target, LocalDateTime cutoff) {
        // Try to search from database if available
        savage.commoneconomy.storage.EconomyStorage storage = savage.commoneconomy.EconomyManager.getStorage();
        if (storage instanceof savage.commoneconomy.storage.SqlStorage) {
            long cutoffTimestamp = cutoff.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            return storage.searchLogs(target, cutoffTimestamp);
        }

        if (!LOG_FILE.exists()) {
            return java.util.Collections.emptyList();
        }

        java.util.List<LogEntry> results = new java.util.ArrayList<>();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(LOG_FILE))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Line format: [yyyy-MM-dd HH:mm:ss] [TYPE] Source -> Target: $Amount (Details)
                if (line.length() < 21) continue;

                try {
                    // Parse timestamp
                    String timestampStr = line.substring(1, 20);
                    LocalDateTime timestamp = LocalDateTime.parse(timestampStr, DATE_FORMAT);
                    
                    if (timestamp.isAfter(cutoff)) {
                        if (target.equals("*") || line.toLowerCase().contains(target.toLowerCase())) {
                            // Parse the rest of the line
                            // Expected: [TYPE] Source -> Target: $Amount (Details)
                            String rest = line.substring(22);
                            int typeEnd = rest.indexOf(']');
                            String type = rest.substring(1, typeEnd);
                            
                            String content = rest.substring(typeEnd + 2); // Skip "] "
                            String[] parts = content.split(" -> ");
                            String source = parts[0];
                            
                            String remaining = parts[1];
                            int amountStart = remaining.indexOf(": $");
                            String targetName = remaining.substring(0, amountStart);
                            
                            String amountAndDetails = remaining.substring(amountStart + 3);
                            int detailsStart = amountAndDetails.indexOf(" (");
                            String amountStr = amountAndDetails.substring(0, detailsStart);
                            String details = amountAndDetails.substring(detailsStart + 2, amountAndDetails.length() - 1);
                            
                            results.add(new LogEntry(timestamp, type, source, targetName, new BigDecimal(amountStr), details));
                        }
                    }
                } catch (Exception e) {
                    // Ignore malformed lines, but maybe log debug if needed
                    // SavsCommonEconomy.LOGGER.warn("Malformed log line: " + line);
                }
            }
        } catch (IOException e) {
            SavsCommonEconomy.LOGGER.error("Failed to read economy log", e);
        }
        
        // Reverse to show newest first
        java.util.Collections.reverse(results);
        return results;
    }

    public static class LogEntry {
        public final LocalDateTime timestamp;
        public final String type;
        public final String source;
        public final String target;
        public final BigDecimal amount;
        public final String details;

        public LogEntry(LocalDateTime timestamp, String type, String source, String target, BigDecimal amount, String details) {
            this.timestamp = timestamp;
            this.type = type;
            this.source = source;
            this.target = target;
            this.amount = amount;
            this.details = details;
        }
    }
}
