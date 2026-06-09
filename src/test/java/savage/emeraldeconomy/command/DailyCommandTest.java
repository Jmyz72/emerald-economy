package savage.emeraldeconomy.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DailyCommandTest {

    @Test
    void formatsHoursAndMinutes() {
        long d = (2L * 3600 + 30 * 60) * 1000L; // 2h 30m
        assertEquals("2h 30m", DailyCommand.formatDuration(d));
    }

    @Test
    void formatsMinutesOnlyUnderAnHour() {
        long d = 45L * 60 * 1000L;
        assertEquals("45m", DailyCommand.formatDuration(d));
    }

    @Test
    void formatsSubMinuteAsLessThanOne() {
        assertEquals("<1m", DailyCommand.formatDuration(30_000L));
    }

    @Test
    void formatsExactHourWithZeroMinutes() {
        assertEquals("1h 0m", DailyCommand.formatDuration(60L * 60 * 1000L));
    }

    @Test
    void formatsZeroAsLessThanOne() {
        assertEquals("<1m", DailyCommand.formatDuration(0L));
    }
}
