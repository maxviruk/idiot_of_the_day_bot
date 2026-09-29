package com.UserOfTheDayBot.model;

import java.time.LocalDate;

/** Одна победа в игре: дата и id победителя. */
public record Win(LocalDate date, long userId) {
}
