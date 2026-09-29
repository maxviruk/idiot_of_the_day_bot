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

    /**
     * Имя для списков (статистика, история): username без «@», либо имя.
     * Без «@», чтобы вывод статистики не пинговал всех игроков.
     */
    public String getDisplayName() {
        return isNullName(username) ? safe(firstName) : username;
    }

    /**
     * HTML-упоминание для объявления победителя: @username, а если username нет —
     * ссылка tg://user, чтобы человек без ника тоже получил уведомление.
     */
    public String getMentionHtml() {
        if (!isNullName(username)) {
            return "@" + Html.escape(username);
        }
        return "<a href=\"tg://user?id=" + id + "\">" + Html.escape(safe(firstName)) + "</a>";
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
