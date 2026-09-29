package com.UserOfTheDayBot.exceptions;

/**
 * Бросается, когда пользователь уже зарегистрирован в игре в этом чате.
 * (Переименовано из existedUserException в соответствии с соглашением:
 * имена классов в Java начинаются с заглавной буквы.)
 */
public class ExistedUserException extends Exception {
    public ExistedUserException() {
        super("Игрок уже в игре");
    }
}
