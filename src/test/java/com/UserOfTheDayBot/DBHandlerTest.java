package com.UserOfTheDayBot;

import com.UserOfTheDayBot.enums.Games;
import com.UserOfTheDayBot.exceptions.ExistedUserException;
import com.UserOfTheDayBot.model.HistoryEntry;
import com.UserOfTheDayBot.model.WinCount;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;

import static com.UserOfTheDayBot.TestUpdates.user;
import static org.junit.jupiter.api.Assertions.*;

class DBHandlerTest {

    private static final long CHAT = -100L;
    private static final LocalDate DAY = LocalDate.of(2026, 9, 29);

    private DBHandler db;

    @BeforeEach
    void setUp() {
        db = new DBHandler("jdbc:sqlite::memory:");
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private UserForBD player(long id) {
        return db.getListOfPlayers(CHAT).stream().filter(p -> p.getId() == id).findFirst().orElseThrow();
    }

    @Test
    void registrationTwiceThrows() throws Exception {
        db.registration(CHAT, user(1, "Bob", "bob"));
        assertThrows(ExistedUserException.class, () -> db.registration(CHAT, user(1, "Bob", "bob")));
        assertEquals(1, db.getListOfPlayers(CHAT).size());
    }

    @Test
    void unregisterKeepsCountersForComeback() throws Exception {
        db.registration(CHAT, user(1, "Bob", "bob"));
        db.saveWinner(CHAT, player(1), DAY, Games.user_of_the_day);

        assertTrue(db.unregister(CHAT, 1));
        assertFalse(db.unregister(CHAT, 1), "повторный выход ничего не меняет");
        assertTrue(db.getListOfPlayers(CHAT).isEmpty());

        db.registration(CHAT, user(1, "Bob", "bob"));
        assertEquals(1, player(1).getUserDayCounter());
    }

    @Test
    void saveWinnerIncrementsCounterAndWritesHistory() throws Exception {
        db.registration(CHAT, user(1, "Bob", "bob"));
        db.saveWinner(CHAT, player(1), DAY, Games.loser_of_the_day);

        assertEquals(1, player(1).getLoserDayCounter());
        assertEquals(0, player(1).getUserDayCounter());
        assertEquals(1, db.getWinnerOn(CHAT, Games.loser_of_the_day, DAY).getId());
        assertNull(db.getWinnerOn(CHAT, Games.user_of_the_day, DAY));
        assertNull(db.getWinnerOn(CHAT, Games.loser_of_the_day, DAY.plusDays(1)));
        assertNull(db.getWinnerOn(CHAT, Games.loser_of_the_day, DAY.plusYears(1)),
                "через год в тот же день розыгрыш снова доступен");
    }

    @Test
    void historyIsNewestFirstAndUsesCurrentNames() throws Exception {
        db.registration(CHAT, user(1, "Bob", "bob"));
        db.saveWinner(CHAT, player(1), DAY.minusDays(1), Games.user_of_the_day);
        db.saveWinner(CHAT, player(1), DAY, Games.loser_of_the_day);
        db.refreshUser(user(1, "Bobby", "bobby"));

        List<HistoryEntry> history = db.getHistory(CHAT, null, 20);
        assertEquals(2, history.size());
        assertEquals(DAY, history.get(0).date);
        assertEquals("bobby", history.get(0).winnerName);
        assertEquals(1, db.getHistory(CHAT, Games.user_of_the_day, 20).size());
    }

    @Test
    void refreshUserDoesNotCreateUnknownUsers() {
        db.refreshUser(user(42, "Stranger", null));
        assertTrue(db.getListOfPlayers(CHAT).isEmpty());
    }

    @Test
    void resetClearsCountersAndHistory() throws Exception {
        db.registration(CHAT, user(1, "Bob", "bob"));
        db.saveWinner(CHAT, player(1), DAY, Games.user_of_the_day);

        db.resetChatStatistics(CHAT);

        assertEquals(0, player(1).getUserDayCounter());
        assertTrue(db.getHistory(CHAT, null, 20).isEmpty());
        assertNull(db.getWinnerOn(CHAT, Games.user_of_the_day, DAY));
    }

    @Test
    void migrateChatMovesEverythingAndIsIdempotent() throws Exception {
        long newChat = -1001234L;
        db.registration(CHAT, user(1, "Bob", "bob"));
        db.saveWinner(CHAT, player(1), DAY, Games.user_of_the_day);

        db.migrateChat(CHAT, newChat);
        db.migrateChat(CHAT, newChat);

        assertTrue(db.getListOfPlayers(CHAT).isEmpty());
        assertEquals(1, db.getListOfPlayers(newChat).size());
        assertEquals(1, db.getListOfPlayers(newChat).get(0).getUserDayCounter());
        assertEquals(1, db.getHistory(newChat, null, 20).size());
    }

    @Test
    void migratesVersion0Database(@TempDir Path dir) throws Exception {
        String url = "jdbc:sqlite:" + dir.resolve("old.db");
        try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement()) {
            // схема v2.0 без колонки active
            st.execute("CREATE TABLE chats (chat_id INTEGER PRIMARY KEY, user_of_the_day TEXT, " +
                       "loser_of_the_day TEXT, user_of_the_day_run_day INTEGER, loser_of_the_day_run_day INTEGER)");
            st.execute("CREATE TABLE users (user_id INTEGER PRIMARY KEY, username TEXT, firstname TEXT)");
            st.execute("CREATE TABLE chat_user (chat_id INTEGER NOT NULL, user_id INTEGER NOT NULL, " +
                       "user_day_counter INTEGER NOT NULL DEFAULT 0, loser_counter INTEGER NOT NULL DEFAULT 0, " +
                       "PRIMARY KEY (chat_id, user_id))");
            st.execute("CREATE TABLE history (id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id INTEGER NOT NULL, " +
                       "game TEXT NOT NULL, winner_user_id INTEGER NOT NULL, winner_name TEXT NOT NULL, " +
                       "run_date TEXT NOT NULL)");
            st.execute("INSERT INTO chats (chat_id) VALUES (" + CHAT + ")");
            st.execute("INSERT INTO users VALUES (1, 'bob', 'Bob')");
            st.execute("INSERT INTO chat_user VALUES (" + CHAT + ", 1, 7, 2)");
        }

        DBHandler migrated = new DBHandler(url);
        try {
            List<UserForBD> players = migrated.getListOfPlayers(CHAT);
            assertEquals(1, players.size());
            assertEquals(7, players.get(0).getUserDayCounter());
        } finally {
            migrated.close();
        }
        try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            assertEquals(DBHandler.SCHEMA_VERSION, rs.getInt(1));
        }
    }

    @Test
    void winCountsForPeriod() throws Exception {
        db.registration(CHAT, user(1, "Bob", "bob"));
        db.registration(CHAT, user(2, "Ann", null));
        db.saveWinner(CHAT, player(1), LocalDate.of(2026, 8, 31), Games.user_of_the_day);
        db.saveWinner(CHAT, player(2), LocalDate.of(2026, 9, 1), Games.user_of_the_day);
        db.saveWinner(CHAT, player(2), LocalDate.of(2026, 9, 2), Games.user_of_the_day);
        db.saveWinner(CHAT, player(1), LocalDate.of(2026, 9, 3), Games.loser_of_the_day);

        List<WinCount> september = db.getWinCounts(CHAT, Games.user_of_the_day, LocalDate.of(2026, 9, 1));
        assertEquals(List.of(new WinCount(2, "Ann", 2)), september);
        assertEquals(2, db.getWinCounts(CHAT, Games.user_of_the_day, LocalDate.of(2026, 1, 1)).size());
        assertEquals(3, db.getTimeline(CHAT, Games.user_of_the_day).size());
    }

    @Test
    void autoTime() {
        assertNull(db.getAutoTime(CHAT));
        db.setAutoTime(CHAT, "10:00");
        assertEquals("10:00", db.getAutoTime(CHAT));
        assertTrue(db.getChatsWithAutoTimeReached("09:59").isEmpty());
        assertEquals(List.of(CHAT), db.getChatsWithAutoTimeReached("10:00"));
        assertEquals(List.of(CHAT), db.getChatsWithAutoTimeReached("23:00"));
        db.setAutoTime(CHAT, null);
        assertTrue(db.getChatsWithAutoTimeReached("23:00").isEmpty());
    }

    @Test
    void findPlayerIncludesPlayersWhoLeft() throws Exception {
        db.registration(CHAT, user(1, "Bob", "bob"));
        db.unregister(CHAT, 1);
        assertTrue(db.findPlayer(CHAT, 1).isPresent());
        assertTrue(db.findPlayer(CHAT, 2).isEmpty());
    }
}
