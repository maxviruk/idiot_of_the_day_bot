package com.UserOfTheDayBot;

import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatAdministrators;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/** TelegramClient для тестов: запоминает все вызовы execute(...) и ничего не отправляет. */
final class FakeTelegramClient {

    final List<Object> calls = new CopyOnWriteArrayList<>();
    volatile ArrayList<ChatMember> admins = new ArrayList<>();

    final TelegramClient client = (TelegramClient) Proxy.newProxyInstance(
            TelegramClient.class.getClassLoader(),
            new Class<?>[]{TelegramClient.class},
            (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return switch (method.getName()) {
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> "FakeTelegramClient";
                    };
                }
                Object arg = args != null && args.length > 0 ? args[0] : null;
                calls.add(arg);
                if (arg instanceof GetChatAdministrators) {
                    return admins;
                }
                if (method.getReturnType() == Boolean.class || method.getReturnType() == boolean.class) {
                    return Boolean.TRUE;
                }
                return null;
            });

    List<SendMessage> sentMessages() {
        List<SendMessage> result = new ArrayList<>();
        for (Object c : calls) {
            if (c instanceof SendMessage m) {
                result.add(m);
            }
        }
        return result;
    }

    List<String> sentTexts() {
        return sentMessages().stream().map(SendMessage::getText).toList();
    }

    String lastText() {
        List<String> texts = sentTexts();
        return texts.isEmpty() ? null : texts.get(texts.size() - 1);
    }

    /** Ждёт (для отложенных сообщений анимации), пока не появится подходящее сообщение. */
    SendMessage awaitMessage(Predicate<SendMessage> condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            for (SendMessage m : sentMessages()) {
                if (condition.test(m)) {
                    return m;
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Не дождались сообщения. Отправлено: " + sentTexts());
    }

    void clear() {
        calls.clear();
    }
}
