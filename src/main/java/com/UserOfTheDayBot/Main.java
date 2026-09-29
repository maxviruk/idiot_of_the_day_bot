package com.UserOfTheDayBot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.meta.api.methods.GetMe;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.Clock;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);
    private static final long MESSAGE_DELAY_MS = 1500;

    public static void main(String[] args) throws Exception {
        Config config = new Config();
        DBHandler dbHandler = new DBHandler(config);
        TelegramClient telegramClient = new OkHttpTelegramClient(config.botToken);

        // username можно не задавать — узнаем его у Telegram
        String username = config.botUsername.isBlank()
                ? telegramClient.execute(new GetMe()).getUserName()
                : config.botUsername;

        Bot bot = new Bot(telegramClient, dbHandler, username, Clock.system(config.zoneId), MESSAGE_DELAY_MS);
        bot.registerCommands();
        bot.startAutoDraws();

        TelegramBotsLongPollingApplication app = new TelegramBotsLongPollingApplication();
        // Корректное завершение по docker stop / Ctrl+C: перестаём получать апдейты,
        // гасим поток анимаций и закрываем базу.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Останавливаюсь...");
            try {
                app.close();
            } catch (Exception e) {
                log.warn("Ошибка при остановке long polling", e);
            }
            bot.shutdown();
            dbHandler.close();
        }, "shutdown"));

        try {
            app.registerBot(config.botToken, bot);
        } catch (TelegramApiException e) {
            log.error("Не удалось запустить бота", e);
            System.exit(1);
        }
        log.info("Бот @{} запущен.", username);
        Thread.currentThread().join();
    }
}
