package savage.emeraldeconomy.storage;

/**
 * Thrown when an economy storage operation fails in a way that must not be
 * silently swallowed (a balance/account read or a state-mutating write).
 * Surfacing this as an unchecked exception lets a database fault propagate to
 * the command layer instead of being read as $0 or an empty result.
 */
public class EconomyStorageException extends RuntimeException {
    public EconomyStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
