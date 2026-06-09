package com.UserOfTheDayBot;

public class UserForBD {
    // ВАЖНО: id у Telegram давно вышел за пределы int (>2^31).
    // В оригинале было int -> регистрация новых пользователей падала.
    private final long id;
    private final String username;
    private final String firstName;
    private int userDayCounter;
    private int loserDayCounter;

    public UserForBD(long id, String username, String firstName) {
        this.id = id;
        this.username = username;
        this.firstName = firstName;
    }

    /** Имя для записи в БД / сравнений. */
    public String getName() {
        return isNullName(username) ? safe(firstName) : username;
    }

    /** Имя для упоминания в чате: @username, либо имя, если username нет. */
    public String getNotificationName() {
        return isNullName(username) ? safe(firstName) : "@" + username;
    }

    public long getId() {
        return id;
    }

    public void setUserDayCounter(int n) {
        this.userDayCounter = n;
    }

    public int getUserDayCounter() {
        return userDayCounter;
    }

    public int getLoserDayCounter() {
        return loserDayCounter;
    }

    public void setLoserDayCounter(int loserDayCounter) {
        this.loserDayCounter = loserDayCounter;
    }

    private static boolean isNullName(String s) {
        return s == null || s.isEmpty() || "null".equals(s);
    }

    private static String safe(String s) {
        return (s == null || s.isEmpty()) ? "Аноним" : s;
    }
}
