package com.UserOfTheDayBot;

/** Экранирование текста для сообщений с parse_mode=HTML. */
public final class Html {

    private Html() {
    }

    public static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
