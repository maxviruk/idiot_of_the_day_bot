package com.UserOfTheDayBot;

import com.UserOfTheDayBot.enums.Games;
import com.UserOfTheDayBot.exceptions.ExistedUserException;
import com.UserOfTheDayBot.model.HistoryEntry;
import com.UserOfTheDayBot.model.Win;
import com.UserOfTheDayBot.model.WinCount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.objects.User;

import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Работа с SQLite.
 *
 * Вся база — один файл (см. db.url, по умолчанию bot.db рядом с программой).
 * Отдельный сервер БД не нужен. Таблицы создаются автоматически при старте,
 * а старые базы обновляются миграциями (PRAGMA user_version).
 *
 * Соединение одно на всё приложение, доступ к методам синхронизирован —
 * для бота в одном экземпляре этого достаточно и исключает ошибки блокировок.
 *
 * Источник правды о розыгрышах — таблица history: «сегодня уже играли»
 * означает, что в history есть запись этой игры с сегодняшней датой.
 */
public class DBHandler {

    /** Текущая версия схемы. Увеличивать при добавлении миграции в {@link #migrate()}. */
    static final int SCHEMA_VERSION = 2;

    private static final Logger log = LoggerFactory.getLogger(DBHandler.class);

    private final Connection connection;

    public DBHandler(Config config) {
        this(config.dbUrl);
    }

    public DBHandler(String dbUrl) {
        try {
            connection = DriverManager.getConnection(dbUrl);
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");   // нормальная конкурентность чтения/записи
                st.execute("PRAGMA busy_timeout=5000");  // ждать вместо мгновенной ошибки "locked"
                st.execute("PRAGMA foreign_keys=ON");
            }
            initSchema();
            migrate();
        } catch (SQLException e) {
            throw new RuntimeException("Не удалось открыть базу: " + dbUrl, e);
        }
    }

    /** Создаёт таблицы в исходном (версии 0) виде, если их ещё нет. */
    private void initSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            // Колонки user_of_the_day* / loser_of_the_day* в chats больше не используются
            // (всё берётся из history), но оставлены для совместимости со старыми базами.
            st.execute("CREATE TABLE IF NOT EXISTS chats (" +
                    "chat_id INTEGER PRIMARY KEY," +
                    "user_of_the_day TEXT," +
                    "loser_of_the_day TEXT," +
                    "user_of_the_day_run_day INTEGER," +
                    "loser_of_the_day_run_day INTEGER)");

            st.execute("CREATE TABLE IF NOT EXISTS users (" +
                    "user_id INTEGER PRIMARY KEY," +
                    "username TEXT," +
                    "firstname TEXT)");

            st.execute("CREATE TABLE IF NOT EXISTS chat_user (" +
                    "chat_id INTEGER NOT NULL," +
                    "user_id INTEGER NOT NULL," +
                    "user_day_counter INTEGER NOT NULL DEFAULT 0," +
                    "loser_counter INTEGER NOT NULL DEFAULT 0," +
                    "PRIMARY KEY (chat_id, user_id)," +
                    "FOREIGN KEY (chat_id) REFERENCES chats(chat_id) ON DELETE CASCADE," +
                    "FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE)");

            st.execute("CREATE TABLE IF NOT EXISTS history (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "chat_id INTEGER NOT NULL," +
                    "game TEXT NOT NULL," +
                    "winner_user_id INTEGER NOT NULL," +
                    "winner_name TEXT NOT NULL," +
                    "run_date TEXT NOT NULL)");   // дата в формате yyyy-MM-dd
            st.execute("CREATE INDEX IF NOT EXISTS idx_history_chat ON history(chat_id, run_date)");
        }
    }

    /** Пошаговые миграции схемы. Каждый шаг выполняется один раз. */
    private void migrate() throws SQLException {
        int version;
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            version = rs.next() ? rs.getInt(1) : 0;
        }
        if (version < 1) {
            inTransaction(() -> {
                try (Statement st = connection.createStatement()) {
                    // Выход из игры больше не удаляет строку со счётчиками — только снимает флаг.
                    st.execute("ALTER TABLE chat_user ADD COLUMN active INTEGER NOT NULL DEFAULT 1");
                    st.execute("PRAGMA user_version = 1");
                }
            });
        }
        if (version < 2) {
            inTransaction(() -> {
                try (Statement st = connection.createStatement()) {
                    // время ежедневного авторозыгрыша (HH:mm в часовом поясе бота), NULL = выключен
                    st.execute("ALTER TABLE chats ADD COLUMN auto_time TEXT");
                    st.execute("PRAGMA user_version = 2");
                }
            });
        }
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            log.error("Ошибка работы с базой", e);
        }
    }

    // ---------------------------------------------------------------------
    // Регистрация / выход
    // ---------------------------------------------------------------------

    /** Зарегистрирован ли и активен ли игрок в чате. */
    public synchronized boolean isRegistered(long chatId, long userId) {
        String sql = "SELECT 1 FROM chat_user WHERE chat_id = ? AND user_id = ? AND active = 1";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, chatId);
            ps.setLong(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            log.error("Ошибка работы с базой", e);
        }
        return false;
    }

    /**
     * Добавляет игрока в чат. Если он раньше выходил из игры — возвращает его
     * с сохранёнными счётчиками.
     */
    public synchronized void registration(long chatId, User user) throws ExistedUserException {
        if (isRegistered(chatId, user.getId())) {
            throw new ExistedUserException();
        }
        try {
            inTransaction(() -> {
                ensureChat(chatId);
                upsertUser(user);
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO chat_user (chat_id, user_id) VALUES (?, ?) " +
                        "ON CONFLICT(chat_id, user_id) DO UPDATE SET active = 1")) {
                    ps.setLong(1, chatId);
                    ps.setLong(2, user.getId());
                    ps.executeUpdate();
                }
            });
        } catch (SQLException e) {
            log.error("Ошибка работы с базой", e);
        }
    }

    /**
     * issue #2: убрать игрока из игры в конкретном чате.
     * Счётчики сохраняются — при повторном /reg игрок продолжит с ними.
     */
    public synchronized boolean unregister(long chatId, long userId) {
        String sql = "UPDATE chat_user SET active = 0 WHERE chat_id = ? AND user_id = ? AND active = 1";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, chatId);
            ps.setLong(2, userId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Ошибка работы с базой", e);
        }
        return false;
    }

    /**
     * Обновляет username / имя уже известного боту пользователя, чтобы
     * в статистике не висели старые ники. Неизвестных пользователей не трогает.
     */
    public synchronized void refreshUser(User user) {
        String sql = "UPDATE users SET username = ?, firstname = ? " +
                     "WHERE user_id = ? AND (username IS NOT ? OR firstname IS NOT ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, user.getUserName());
            ps.setString(2, user.getFirstName());
            ps.setLong(3, user.getId());
            ps.setString(4, user.getUserName());
            ps.setString(5, user.getFirstName());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Ошибка работы с базой", e);
        }
    }

    /**
     * Группа превратилась в супергруппу — у неё сменился chat_id.
     * Переносим игроков, счётчики и историю на новый id. Повторный вызов безопасен.
     */
    public synchronized void migrateChat(long oldChatId, long newChatId) {
        if (oldChatId == newChatId) {
            return;
        }
        try {
            inTransaction(() -> {
                if (!chatExists(oldChatId)) {
                    return;
                }
                ensureChat(newChatId);
                // Если в новом чате уже кто-то зарегистрировался — эти строки не трогаем,
                // а оставшиеся дубликаты удалятся каскадом вместе со старым чатом.
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE OR IGNORE chat_user SET chat_id = ? WHERE chat_id = ?")) {
                    ps.setLong(1, newChatId);
                    ps.setLong(2, oldChatId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE history SET chat_id = ? WHERE chat_id = ?")) {
                    ps.setLong(1, newChatId);
                    ps.setLong(2, oldChatId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = connection.prepareStatement(
                        "DELETE FROM chats WHERE chat_id = ?")) {
                    ps.setLong(1, oldChatId);
                    ps.executeUpdate();
                }
            });
        } catch (SQLException e) {
            log.error("Ошибка работы с базой", e);
        }
    }

    // ---------------------------------------------------------------------
    // Игроки и розыгрыш
    // ---------------------------------------------------------------------

    /** Активные игроки чата. */
    public synchronized List<UserForBD> getListOfPlayers(long chatId) {
        List<UserForBD> players = new ArrayList<>();
        String sql = "SELECT u.user_id, u.username, u.firstname, cu.user_day_counter, cu.loser_counter " +
                     "FROM users u JOIN chat_user cu ON cu.user_id = u.user_id " +
                     "WHERE cu.chat_id = ? AND cu.active = 1";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, chatId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UserForBD u = new UserForBD(rs.getLong(1), rs.getString(2), rs.getString(3));
                    u.setUserDayCounter(rs.getInt(4));
                    u.setLoserDayCounter(rs.getInt(5));
                    players.add(u);
                }
            }
        } catch (SQLException e) {
            log.error("Ошибка работы с базой", e);
        }
        return players;
    }

    /** Победитель игры за указанную дату или null, если в этот день не играли. */
    public synchronized UserForBD getWinnerOn(long chatId, Games game, LocalDate date) {
        String sql = "SELECT h.winner_user_id, u.username, u.firstname, h.winner_name " +
                     "FROM history h LEFT JOIN users u ON u.user_id = h.winner_user_id " +
                     "WHERE h.chat_id = ? AND h.game = ? AND h.run_date = ? " +
                     "ORDER BY h.id DESC LIMIT 1";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, chatId);
            ps.setString(2, game.name());
            ps.setString(3, date.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String username = rs.getString(2);
                    String firstname = rs.getString(3);
                    if (username == null && firstname == null) {
                        // пользователя нет в users — берём имя, сохранённое в истории
                        firstname = rs.getString(4);
                    }
                    return new UserForBD(rs.getLong(1), username, firstname);
                }
            }
        } catch (SQLException e) {
            log.error("Ошибка работы с базой", e);
        }
        return null;
    }

    /**
     * Записывает победителя: +1 к счётчику и запись в history с датой {@code date}
     * (дата должна быть в часовом поясе бота). Всё в одной транзакции.
     */
    public synchronized void saveWinner(long chatId, UserForBD user, LocalDate date, Games game) {
        String counterColumn = game == Games.user_of_the_day ? "user_day_counter" : "loser_counter";
        try {
            inTransaction(() -> {
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE chat_user SET " + counterColumn + " = " + counterColumn + " + 1 " +
                        "WHERE chat_id = ? AND user_id = ?")) {
                    ps.setLong(1, chatId);
                    ps.setLong(2, user.getId());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO history (chat_id, game, winner_user_id, winner_name, run_date) " +
                        "VALUES (?, ?, ?, ?, ?)")) {
                    ps.setLong(1, chatId);
                    ps.setString(2, game.name());
                    ps.setLong(3, user.getId());
                    ps.setString(4, user.getDisplayName());
                    ps.setString(5, date.toString());   // ISO yyyy-MM-dd
                    ps.executeUpdate();
                }
            });
        } catch (SQLException e) {
            log.error("Ошибка работы с базой", e);
        }
    }

    // ---------------------------------------------------------------------
    // История победителей
    // ---------------------------------------------------------------------

    /** Последние N записей истории. game == null -> обе игры. */
    public synchronized List<HistoryEntry> getHistory(long chatId, Games game, int limit) {
        List<HistoryEntry> result = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT h.run_date, h.game, h.winner_name, u.username, u.firstname " +
                "FROM history h LEFT JOIN users u ON u.user_id = h.winner_user_id " +
                "WHERE h.chat_id = ?");
        if (game != null) {
            sql.append(" AND h.game = ?");
        }
        sql.append(" ORDER BY h.run_date DESC, h.id DESC LIMIT ?");

        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            int i = 1;
            ps.setLong(i++, chatId);
            if (game != null) {
                ps.setString(i++, game.name());
            }
            ps.setInt(i, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String username = rs.getString(4);
                    String firstname = rs.getString(5);
                    String name = (username == null && firstname == null)
                            ? rs.getString(3)
                            : new UserForBD(0, username, firstname).getDisplayName();
                    result.add(new HistoryEntry(LocalDate.parse(rs.getString(1)), rs.getString(2), name));
                }
            }
        } catch (SQLException e) {
            log.error("Ошибка работы с базой", e);
        }
        return result;
    }

    // ---------------------------------------------------------------------
    // Статистика за период, серии, личная статистика
    // ---------------------------------------------------------------------

    /** Сколько раз каждый выигрывал игру начиная с {@code from} (включительно), по убыванию. */
    public synchronized List<WinCount> getWinCounts(long chatId, Games game, LocalDate from) {
        List<WinCount> result = new ArrayList<>();
        String sql = "SELECT h.winner_user_id, u.username, u.firstname, MAX(h.winner_name), COUNT(*) AS cnt " +
                     "FROM history h LEFT JOIN users u ON u.user_id = h.winner_user_id " +
                     "WHERE h.chat_id = ? AND h.game = ? AND h.run_date >= ? " +
                     "GROUP BY h.winner_user_id ORDER BY cnt DESC, MAX(h.run_date) ASC";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, chatId);
            ps.setString(2, game.name());
            ps.setString(3, from.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String username = rs.getString(2);
                    String firstname = rs.getString(3);
                    String name = (username == null && firstname == null)
                            ? rs.getString(4)
                            : new UserForBD(0, username, firstname).getDisplayName();
                    result.add(new WinCount(rs.getLong(1), name, rs.getInt(5)));
                }
            }
        } catch (SQLException e) {
            log.error("Ошибка работы с базой", e);
        }
        return result;
    }

    /** Все победы в игре по порядку (старые -> новые) — для подсчёта серий. */
    public synchronized List<Win> getTimeline(long chatId, Games game) {
        List<Win> result = new ArrayList<>();
        String sql = "SELECT run_date, winner_user_id FROM history " +
                     "WHERE chat_id = ? AND game = ? ORDER BY run_date ASC, id ASC";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, chatId);
            ps.setString(2, game.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new Win(LocalDate.parse(rs.getString(1)), rs.getLong(2)));
                }
            }
        } catch (SQLException e) {
            log.error("Ошибка работы с базой", e);
        }
        return result;
    }

    /**
     * Игрок чата со счётчиками — в том числе вышедший из игры (для /me).
     * Пусто, если пользователь никогда не регистрировался в этом чате.
     */
    public synchronized Optional<UserForBD> findPlayer(long chatId, long userId) {
        String sql = "SELECT u.user_id, u.username, u.firstname, cu.user_day_counter, cu.loser_counter " +
                     "FROM users u JOIN chat_user cu ON cu.user_id = u.user_id " +
                     "WHERE cu.chat_id = ? AND cu.user_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, chatId);
            ps.setLong(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    UserForBD u = new UserForBD(rs.getLong(1), rs.getString(2), rs.getString(3));
                    u.setUserDayCounter(rs.getInt(4));
                    u.setLoserDayCounter(rs.getInt(5));
                    return Optional.of(u);
                }
            }
        } catch (SQLException e) {
            log.error("Ошибка работы с базой", e);
        }
        return Optional.empty();
    }

    // ---------------------------------------------------------------------
    // Авторозыгрыш по расписанию
    // ---------------------------------------------------------------------

    /** Включает авторозыгрыш в {@code time} (формат HH:mm) или выключает, если null. */
    public synchronized void setAutoTime(long chatId, String time) {
        try {
            inTransaction(() -> {
                ensureChat(chatId);
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE chats SET auto_time = ? WHERE chat_id = ?")) {
                    ps.setString(1, time);
                    ps.setLong(2, chatId);
                    ps.executeUpdate();
                }
            });
        } catch (SQLException e) {
            log.error("Ошибка работы с базой", e);
        }
    }

    /** Время авторозыгрыша (HH:mm) или null, если выключен. */
    public synchronized String getAutoTime(long chatId) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT auto_time FROM chats WHERE chat_id = ?")) {
            ps.setLong(1, chatId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
            }
        } catch (SQLException e) {
            log.error("Ошибка работы с базой", e);
        }
        return null;
    }

    /** Чаты, у которых время авторозыгрыша уже наступило ({@code now} — HH:mm). */
    public synchronized List<Long> getChatsWithAutoTimeReached(String now) {
        List<Long> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT chat_id FROM chats WHERE auto_time IS NOT NULL AND auto_time <= ?")) {
            ps.setString(1, now);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getLong(1));
                }
            }
        } catch (SQLException e) {
            log.error("Ошибка работы с базой", e);
        }
        return result;
    }

    // ---------------------------------------------------------------------
    // Сброс статистики (issue #5)
    // ---------------------------------------------------------------------

    public synchronized void resetChatStatistics(long chatId) {
        try {
            inTransaction(() -> {
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE chat_user SET user_day_counter = 0, loser_counter = 0 WHERE chat_id = ?")) {
                    ps.setLong(1, chatId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = connection.prepareStatement(
                        "DELETE FROM history WHERE chat_id = ?")) {
                    ps.setLong(1, chatId);
                    ps.executeUpdate();
                }
            });
        } catch (SQLException e) {
            log.error("Ошибка работы с базой", e);
        }
    }

    // ---------------------------------------------------------------------
    // Вспомогательное
    // ---------------------------------------------------------------------

    @FunctionalInterface
    private interface SqlWork {
        void run() throws SQLException;
    }

    private void inTransaction(SqlWork work) throws SQLException {
        connection.setAutoCommit(false);
        try {
            work.run();
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private boolean chatExists(long chatId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM chats WHERE chat_id = ?")) {
            ps.setLong(1, chatId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void ensureChat(long chatId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR IGNORE INTO chats (chat_id) VALUES (?)")) {
            ps.setLong(1, chatId);
            ps.executeUpdate();
        }
    }

    private void upsertUser(User user) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO users (user_id, username, firstname) VALUES (?, ?, ?) " +
                "ON CONFLICT(user_id) DO UPDATE SET username = excluded.username, " +
                "firstname = excluded.firstname")) {
            ps.setLong(1, user.getId());
            ps.setString(2, user.getUserName());
            ps.setString(3, user.getFirstName());
            ps.executeUpdate();
        }
    }
}
