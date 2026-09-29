-- =====================================================================
-- Схема БД для TheUserOfTheDayBot (SQLite).
--
-- ВАЖНО: запускать этот файл вручную НЕ обязательно — бот создаёт все
-- таблицы сам при первом старте (DBHandler.initSchema()). Файл оставлен
-- как справочник по структуре (issue #4: "не хватает схемы базы данных").
--
-- Если всё же хотите создать базу заранее:
--   sqlite3 bot.db < sql/schema.sql
-- =====================================================================

PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;

-- Чаты, где работает бот
CREATE TABLE IF NOT EXISTS chats (
    chat_id                  INTEGER PRIMARY KEY,
    user_of_the_day          TEXT,      -- имя последнего "красавчика"
    loser_of_the_day         TEXT,      -- имя последнего "неудачника"
    user_of_the_day_run_day  INTEGER,   -- день года последнего розыгрыша
    loser_of_the_day_run_day INTEGER
);

-- Пользователи (глобально). INTEGER в SQLite 64-битный — Telegram id влезает.
CREATE TABLE IF NOT EXISTS users (
    user_id   INTEGER PRIMARY KEY,
    username  TEXT,
    firstname TEXT
);

-- Связь "пользователь участвует в игре в этом чате" + счётчики
CREATE TABLE IF NOT EXISTS chat_user (
    chat_id          INTEGER NOT NULL,
    user_id          INTEGER NOT NULL,
    user_day_counter INTEGER NOT NULL DEFAULT 0,
    loser_counter    INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (chat_id, user_id),
    FOREIGN KEY (chat_id) REFERENCES chats(chat_id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
);

-- История победителей (для команды /history). Дата хранится как текст yyyy-MM-dd.
CREATE TABLE IF NOT EXISTS history (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    chat_id        INTEGER NOT NULL,
    game           TEXT NOT NULL,   -- user_of_the_day / loser_of_the_day
    winner_user_id INTEGER NOT NULL,
    winner_name    TEXT NOT NULL,
    run_date       TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_history_chat ON history(chat_id, run_date);
