package com.UserOfTheDayBot.model;

import java.time.LocalDate;

/** Одна запись истории победителей (для команды /history). */
public class HistoryEntry {
    public final LocalDate date;
    public final String game;       // user_of_the_day / loser_of_the_day
    public final String winnerName;

    public HistoryEntry(LocalDate date, String game, String winnerName) {
        this.date = date;
        this.game = game;
        this.winnerName = winnerName;
    }
}
