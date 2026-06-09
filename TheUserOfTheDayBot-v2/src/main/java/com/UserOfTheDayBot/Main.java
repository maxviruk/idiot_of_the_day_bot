package com.UserOfTheDayBot;

import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

public class Main {

    public static void main(String[] args) {
        Config config = new Config();
        DBHandler dbHandler = new DBHandler(config);

        try {
            // В новой версии API ApiContextInitializer больше не нужен.
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(new Bot(config, dbHandler));
            System.out.println("Бот @" + config.botUsername + " запущен.");
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }
}
