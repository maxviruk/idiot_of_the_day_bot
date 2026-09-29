package com.UserOfTheDayBot;

import com.UserOfTheDayBot.model.Win;

import java.time.LocalDate;
import java.util.List;

/**
 * Серии побед: сколько дней подряд игрок выигрывал одну и ту же игру.
 * День без розыгрыша серию прерывает.
 */
public final class Streaks {

    /**
     * @param current текущая серия (0, если последняя победа игрока была не вчера и не сегодня
     *                или после неё уже выигрывал кто-то другой)
     * @param best    рекордная серия
     */
    public record Streak(int current, int best) {
    }

    private Streaks() {
    }

    /**
     * @param timeline победы в игре по возрастанию даты (см. {@link DBHandler#getTimeline})
     * @param today    «сегодня» в часовом поясе бота
     */
    public static Streak of(List<Win> timeline, long userId, LocalDate today) {
        int best = 0;
        int run = 0;
        LocalDate runEnd = null;
        LocalDate prevDate = null;
        long prevWinner = Long.MIN_VALUE;
        for (Win w : timeline) {
            if (w.date().equals(prevDate)) {
                // в один день несколько записей быть не должно, но на всякий случай учитываем последнюю
                if (w.userId() != prevWinner) {
                    run = w.userId() == userId ? 1 : 0;
                }
            } else if (w.userId() == userId) {
                boolean continues = prevWinner == userId && prevDate != null && prevDate.plusDays(1).equals(w.date());
                run = continues ? run + 1 : 1;
            } else {
                run = 0;
            }
            prevDate = w.date();
            prevWinner = w.userId();
            if (w.userId() == userId) {
                runEnd = w.date();
            }
            best = Math.max(best, run);
        }
        boolean lastIsUsers = prevWinner == userId;
        boolean fresh = runEnd != null && !runEnd.isBefore(today.minusDays(1));
        return new Streak(lastIsUsers && fresh ? run : 0, best);
    }

    /** «1 день», «2 дня», «5 дней». */
    public static String days(int n) {
        int mod100 = n % 100;
        int mod10 = n % 10;
        String word;
        if (mod100 >= 11 && mod100 <= 14) {
            word = "дней";
        } else if (mod10 == 1) {
            word = "день";
        } else if (mod10 >= 2 && mod10 <= 4) {
            word = "дня";
        } else {
            word = "дней";
        }
        return n + " " + word;
    }
}
