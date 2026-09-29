package com.UserOfTheDayBot;

import com.UserOfTheDayBot.model.Win;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StreaksTest {

    private static final LocalDate D1 = LocalDate.of(2026, 9, 1);

    private static Win win(int day, long user) {
        return new Win(D1.plusDays(day - 1), user);
    }

    @Test
    void emptyTimeline() {
        assertEquals(new Streaks.Streak(0, 0), Streaks.of(List.of(), 1, D1));
    }

    @Test
    void currentAndBest() {
        List<Win> timeline = List.of(win(1, 1), win(2, 1), win(3, 1), win(4, 2), win(5, 1), win(6, 1));
        assertEquals(new Streaks.Streak(2, 3), Streaks.of(timeline, 1, D1.plusDays(5)));
        assertEquals(new Streaks.Streak(0, 1), Streaks.of(timeline, 2, D1.plusDays(5)));
    }

    @Test
    void dayWithoutDrawBreaksStreak() {
        List<Win> timeline = List.of(win(1, 1), win(2, 1), win(4, 1));
        assertEquals(new Streaks.Streak(1, 2), Streaks.of(timeline, 1, D1.plusDays(3)));
    }

    @Test
    void streakFadesWhenLastWinIsOld() {
        List<Win> timeline = List.of(win(1, 1), win(2, 1));
        assertEquals(2, Streaks.of(timeline, 1, D1.plusDays(2)).current(), "вчерашняя серия ещё жива");
        assertEquals(0, Streaks.of(timeline, 1, D1.plusDays(3)).current());
        assertEquals(2, Streaks.of(timeline, 1, D1.plusDays(3)).best());
    }

    @Test
    void russianPlural() {
        assertEquals("1 день", Streaks.days(1));
        assertEquals("3 дня", Streaks.days(3));
        assertEquals("5 дней", Streaks.days(5));
        assertEquals("11 дней", Streaks.days(11));
        assertEquals("21 день", Streaks.days(21));
        assertEquals("0 дней", Streaks.days(0));
    }
}
