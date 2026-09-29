package com.UserOfTheDayBot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.ZoneId;
import java.util.Properties;

/**
 * Конфигурация бота.
 * Значения берутся из config.properties (путь можно задать через переменную
 * окружения CONFIG_PATH, по умолчанию ./config.properties) либо из переменных
 * окружения. Никаких логинов/паролей к БД больше нет — SQLite их не требует.
 */
public class Config {

    private static final Logger log = LoggerFactory.getLogger(Config.class);

    public final String botUsername;   // необязателен: если пуст, берётся из getMe
    public final String botToken;
    public final String dbUrl;     // jdbc:sqlite:bot.db
    public final ZoneId zoneId;

    public Config() {
        Properties props = new Properties();
        String path = envOr("CONFIG_PATH", "config.properties");
        try (InputStream in = new FileInputStream(path)) {
            props.load(in);
        } catch (IOException e) {
            log.info("Файл {} не найден, читаю только переменные окружения.", path);
        }

        this.botUsername = pick(props, "bot.username", "BOT_USERNAME", "");
        this.botToken    = pick(props, "bot.token",    "BOT_TOKEN",    "");
        this.dbUrl       = pick(props, "db.url",       "DB_URL",       "jdbc:sqlite:bot.db");
        this.zoneId      = ZoneId.of(pick(props, "bot.timezone", "BOT_TIMEZONE", "Europe/Prague"));

        if (botToken.isBlank()) {
            throw new IllegalStateException(
                    "Не задан bot.token / BOT_TOKEN. Заполни config.properties (см. config.example.properties) или .env.");
        }
    }

    private static String pick(Properties props, String propKey, String envKey, String def) {
        String env = System.getenv(envKey);
        if (env != null && !env.isBlank()) {
            return env;
        }
        return props.getProperty(propKey, def);
    }

    private static String envOr(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v;
    }
}
