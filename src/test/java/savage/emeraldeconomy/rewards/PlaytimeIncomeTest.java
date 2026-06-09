package savage.emeraldeconomy.rewards;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlaytimeIncomeTest {

    @Test
    void floorsToWholeMinutes() {
        long now = (5L * 60 + 59) * 1000L; // 5m59s -> 5
        assertEquals(5L, PlaytimeIncome.sessionMinutes(0L, now));
    }

    @Test
    void exactMinutesCountExactly() {
        assertEquals(3L, PlaytimeIncome.sessionMinutes(0L, 180_000L));
    }

    @Test
    void negativeIntervalIsZero() {
        assertEquals(0L, PlaytimeIncome.sessionMinutes(100_000L, 0L));
    }
}
