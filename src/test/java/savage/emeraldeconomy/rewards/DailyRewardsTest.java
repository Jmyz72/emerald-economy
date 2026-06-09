package savage.emeraldeconomy.rewards;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DailyRewardsTest {

    private static final long HOUR = 3600_000L;

    @Test
    void neverClaimedIsImmediatelyClaimable() {
        assertEquals(0L, DailyRewards.remainingCooldownMillis(null, 1_000_000L, 24));
    }

    @Test
    void withinCooldownReturnsRemaining() {
        long now = 10_000_000L;
        long last = now - HOUR; // claimed 1h ago, 24h cooldown -> 23h left
        assertEquals(23L * HOUR, DailyRewards.remainingCooldownMillis(last, now, 24));
    }

    @Test
    void pastCooldownReturnsZero() {
        long now = 100_000_000L;
        long last = now - (25L * HOUR); // 25h ago, > 24h cooldown
        assertEquals(0L, DailyRewards.remainingCooldownMillis(last, now, 24));
    }
}
