package com.UserOfTheDayBot;

import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;

/** Сборка Update-ов для тестов. */
final class TestUpdates {

    private static int updateId = 1;

    private TestUpdates() {
    }

    static User user(long id, String firstName, String username) {
        User u = new User(id, firstName, false);
        u.setUserName(username);
        return u;
    }

    static Message message(long chatId, User from, String text) {
        Message m = new Message();
        m.setChat(new Chat(chatId, chatId > 0 ? "private" : "supergroup"));
        m.setFrom(from);
        m.setText(text);
        return m;
    }

    static Update update(Message message) {
        Update u = new Update();
        u.setUpdateId(updateId++);
        u.setMessage(message);
        return u;
    }

    static Update text(long chatId, User from, String text) {
        return update(message(chatId, from, text));
    }
}
