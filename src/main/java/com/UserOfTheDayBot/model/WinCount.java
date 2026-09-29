package com.UserOfTheDayBot.model;

/** Число побед игрока за период. */
public record WinCount(long userId, String name, int count) {
}
