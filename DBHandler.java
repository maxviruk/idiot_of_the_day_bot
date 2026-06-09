package com.UserOfTheDayBot;

import com.UserOfTheDayBot.enums.DBColumns;
import com.UserOfTheDayBot.enums.Games;
import com.UserOfTheDayBot.exceptions.ExistedUserException;
import com.UserOfTheDayBot.model.HistoryEntry;
import org.telegram.telegrambots.meta.api.objects.User;

import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Работа с SQLite.
 *
 * Вся база — один файл (см. db.url, по умолчанию bot.db рядом с программой).
 * Отдельный сервер БД не нужен. Таблицы создаются автоматически при старте,
 * поэтому никаких ручных шагов с базой нет.
 *
 * Соединение одно на всё приложение, доступ к методам синхронизирован —
 * для бота в одном экземпляре этого достаточно и исключает ошибки блокировок.
 */
public class DBHandler {

    private final Connection connection;

    public DBHandler(Config config) {
        try {
            connection = DriverManager.getConnection(config.dbUrl);
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");   // нормальная конкурентность чтения/записи
                st.execute("PRAGMA busy_timeout=5000");  // ждать вместо мгновенной ошибки "locked"
                st.execute("PRAGMA foreign_keys=ON");
            }
            initSchema();
        } catch (SQLException e) {
            throw new RuntimeException("Не удалось открыть базу: " + config.dbUrl, e);
        }
    }

    /** Создаёт таблицы, если их ещё нет. */
    private void initSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
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

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // ---------------------------------------------------------------------
    // Регистрация / выход
    // ---------------------------------------------------------------------

    public synchronized boolean isRegistered(long chatId, long userId) {
        String sql = "SELECT 1 FROM chat_user WHERE chat_id = ? AND user_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, chatId);
            ps.setLong(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    public synchronized void registration(long chatId, User user) throws ExistedUserException {
        long userId = user.getId();
        if (isRegistered(chatId, userId)) {
            throw new ExistedUserException();
        }
        try {
            // 1) родитель: чат
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT OR IGNORE INTO chats (chat_id) VALUES (?)")) {
                ps.setLong(1, chatId);
                ps.executeUpdate();
            }
            // 2) родитель: пользователь (обновляем имя, если уже был)
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO users (user_id, username, firstname) VALUES (?, ?, ?) " +
                    "ON CONFLICT(user_id) DO UPDATE SET username = excluded.username, " +
                    "firstname = excluded.firstname")) {
                ps.setLong(1, userId);
                ps.setString(2, user.getUserName());
                ps.setString(3, user.getFirstName());
                ps.executeUpdate();
            }
            // 3) дочерняя: связь чат-пользователь
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT OR IGNORE INTO chat_user (chat_id, user_id) VALUES (?, ?)")) {
                ps.setLong(1, chatId);
                ps.setLong(2, userId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /** issue #2: убрать игрока из игры в конкретном чате. */
    public synchronized boolean unregister(long chatId, long userId) {
        String sql = "DELETE FROM chat_user WHERE chat_id = ? AND user_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, chatId);
            ps.setLong(2, userId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    // ---------------------------------------------------------------------
    // Игроки и розыгрыш
    // ---------------------------------------------------------------------

    public synchronized List<UserForBD> getListOfPlayers(long chatId) {
        List<UserForBD> players = new ArrayList<>();
        String sql = "SELECT u.user_id, u.username, u.firstname, cu.user_day_counter, cu.loser_counter " +
                     "FROM users u JOIN chat_user cu ON cu.user_id = u.user_id " +
                     "WHERE cu.chat_id = ?";
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
            e.printStackTrace();
        }
        return players;
    }

    public synchronized boolean isTheSameDayRunning(long chatId, int dayOfYear, DBColumns column) {
        String sql = "SELECT " + column + " FROM chats WHERE chat_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, chatId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int stored = rs.getInt(1);
                    return !rs.wasNull() && stored == dayOfYear;
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    public synchronized void setWinnerAndDayRunning(long chatId, UserForBD user, int dayOfYear, Games game) {
        String winnerColumn;
        String dayColumn;
        String counterColumn;
        switch (game) {
            case user_of_the_day:
                winnerColumn = "user_of_the_day";
                dayColumn = "user_of_the_day_run_day";
                counterColumn = "user_day_counter";
                break;
            case loser_of_the_day:
                winnerColumn = "loser_of_the_day";
                dayColumn = "loser_of_the_day_run_day";
                counterColumn = "loser_counter";
                break;
            default:
                return;
        }
        try {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE chats SET " + winnerColumn + " = ?, " + dayColumn + " = ? WHERE chat_id = ?")) {
                ps.setString(1, user.getName());
                ps.setInt(2, dayOfYear);
                ps.setLong(3, chatId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE chat_user SET " + counterColumn + " = " + counterColumn + " + 1 " +
                    "WHERE chat_id = ? AND user_id = ?")) {
                ps.setLong(1, chatId);
                ps.setLong(2, user.getId());
                ps.executeUpdate();
            }
            addHistory(chatId, game, user, LocalDate.now());
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public synchronized String getWinnerOfTheGame(long chatId, Games game) {
        String sql = "SELECT " + game + " FROM chats WHERE chat_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, chatId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // История победителей
    // ---------------------------------------------------------------------

    private void addHistory(long chatId, Games game, UserForBD user, LocalDate date) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO history (chat_id, game, winner_user_id, winner_name, run_date) " +
                "VALUES (?, ?, ?, ?, ?)")) {
            ps.setLong(1, chatId);
            ps.setString(2, game.name());
            ps.setLong(3, user.getId());
            ps.setString(4, user.getNotificationName());
            ps.setString(5, date.toString());   // ISO yyyy-MM-dd
            ps.executeUpdate();
        }
    }

    /** Последние N записей истории. game == null -> обе игры. */
    public synchronized List<HistoryEntry> getHistory(long chatId, Games game, int limit) {
        List<HistoryEntry> result = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT run_date, game, winner_name FROM history WHERE chat_id = ?");
        if (game != null) {
            sql.append(" AND game = ?");
        }
        sql.append(" ORDER BY run_date DESC, id DESC LIMIT ?");

        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            int i = 1;
            ps.setLong(i++, chatId);
            if (game != null) {
                ps.setString(i++, game.name());
            }
            ps.setInt(i, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new HistoryEntry(
                            LocalDate.parse(rs.getString(1)),
                            rs.getString(2),
                            rs.getString(3)));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return result;
    }

    // ---------------------------------------------------------------------
    // Сброс статистики (issue #5)
    // ---------------------------------------------------------------------

    public synchronized void resetChatStatistics(long chatId) {
        try {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE chat_user SET user_day_counter = 0, loser_counter = 0 WHERE chat_id = ?")) {
                ps.setLong(1, chatId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE chats SET user_of_the_day = NULL, loser_of_the_day = NULL, " +
                    "user_of_the_day_run_day = NULL, loser_of_the_day_run_day = NULL WHERE chat_id = ?")) {
                ps.setLong(1, chatId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM history WHERE chat_id = ?")) {
                ps.setLong(1, chatId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
