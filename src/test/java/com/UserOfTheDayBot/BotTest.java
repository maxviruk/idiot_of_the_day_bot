package com.UserOfTheDayBot;

import com.UserOfTheDayBot.enums.Commands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static com.UserOfTheDayBot.TestUpdates.*;
import static org.junit.jupiter.api.Assertions.*;

class BotTest {

    private static final long CHAT = -100L;
    private static final ZoneId ZONE = ZoneId.of("Europe/Prague");
    private static final Duration WAIT = Duration.ofSeconds(5);

    private final User bob = user(1, "Bob", "bob");
    private final User ann = user(2, "Ann", null);

    private DBHandler db;
    private FakeTelegramClient telegram;
    private Bot bot;

    @BeforeEach
    void setUp() {
        db = new DBHandler("jdbc:sqlite::memory:");
        telegram = new FakeTelegramClient();
        bot = newBot(0, Instant.parse("2026-09-29T10:00:00Z"));
    }

    private Bot newBot(long delayMs, Instant now) {
        return new Bot(telegram.client, db, "TestBot", Clock.fixed(now, ZONE), delayMs);
    }

    @AfterEach
    void tearDown() {
        bot.shutdown();
        db.close();
    }

    @Test
    void parseCommand() {
        assertEquals(Commands.run, Bot.parseCommand("/run", "TestBot"));
        assertEquals(Commands.run, Bot.parseCommand("  /RUN@testbot extra", "TestBot"));
        assertNull(Bot.parseCommand("/run@OtherBot", "TestBot"), "команда другому боту");
        assertNull(Bot.parseCommand("/unknown", "TestBot"));
        assertNull(Bot.parseCommand("run", "TestBot"));
    }

    @Test
    void runAnnouncesWinnerAndRepeatsSameWinnerToday() throws Exception {
        bot.consume(text(CHAT, ann, "/reg"));
        bot.consume(text(CHAT, ann, "/run"));

        SendMessage announce = telegram.awaitMessage(m -> m.getText().startsWith("🎉"), WAIT);
        assertEquals("HTML", announce.getParseMode());
        assertTrue(announce.getText().contains("<a href=\"tg://user?id=2\">Ann</a>"), announce.getText());

        telegram.clear();
        bot.consume(text(CHAT, bob, "/reg"));
        bot.consume(text(CHAT, bob, "/run"));
        assertTrue(telegram.lastText().contains("tg://user?id=2"), "победитель дня не меняется");
        assertEquals(1, db.getListOfPlayers(CHAT).stream().mapToInt(UserForBD::getUserDayCounter).sum());
    }

    @Test
    void secondRunDuringAnimationDoesNotSpoilWinner() {
        bot.shutdown();
        bot = newBot(60_000, Instant.parse("2026-09-29T10:00:00Z"));
        bot.consume(text(CHAT, bob, "/reg"));
        bot.consume(text(CHAT, bob, "/run"));
        telegram.clear();

        bot.consume(text(CHAT, bob, "/run"));
        assertTrue(telegram.lastText().startsWith("⏳"), telegram.lastText());
        assertFalse(telegram.lastText().contains("bob"));
    }

    @Test
    void dayUsesBotTimezone() throws Exception {
        // 23:30 UTC 28.09 = 01:30 29.09 по Праге
        bot.shutdown();
        bot = newBot(0, Instant.parse("2026-09-28T23:30:00Z"));
        bot.consume(text(CHAT, bob, "/reg"));
        bot.consume(text(CHAT, bob, "/run"));
        telegram.awaitMessage(m -> m.getText().startsWith("🎉"), WAIT);

        assertEquals("2026-09-29", db.getHistory(CHAT, null, 1).get(0).date.toString());
    }

    @Test
    void runWithoutPlayers() {
        bot.consume(text(CHAT, bob, "/run"));
        assertTrue(telegram.lastText().startsWith("Нет игроков"));
    }

    @Test
    void commandForAnotherBotIsIgnored() {
        bot.consume(text(CHAT, bob, "/reg@OtherBot"));
        assertTrue(telegram.sentTexts().isEmpty());
        assertTrue(db.getListOfPlayers(CHAT).isEmpty());
    }

    @Test
    void leftMemberIsUnregistered() {
        bot.consume(text(CHAT, bob, "/reg"));
        Message left = new Message();
        left.setChat(new Chat(CHAT, "supergroup"));
        left.setLeftChatMember(bob);
        bot.consume(update(left));
        assertTrue(db.getListOfPlayers(CHAT).isEmpty());
    }

    @Test
    void resetRequiresAdmin() {
        bot.consume(text(CHAT, bob, "/reset"));
        assertEquals("Эта команда только для администраторов чата.", telegram.lastText());
    }

    @Test
    void anonymousAdminCanReset() {
        Message m = message(CHAT, user(1087968824L, "Group", "GroupAnonymousBot"), "/reset");
        m.setSenderChat(new Chat(CHAT, "supergroup"));
        bot.consume(update(m));
        assertNotEquals("Эта команда только для администраторов чата.", telegram.lastText());
    }

    @Test
    void groupMigrationMovesPlayers() {
        bot.consume(text(CHAT, bob, "/reg"));
        Message migrate = new Message();
        migrate.setChat(new Chat(CHAT, "group"));
        migrate.setMigrateToChatId(-1009L);
        bot.consume(update(migrate));
        assertEquals(1, db.getListOfPlayers(-1009L).size());
    }

    @Test
    void usernameChangeIsPickedUp() {
        bot.consume(text(CHAT, bob, "/reg"));
        User renamed = user(1, "Bob", "bob_new");
        bot.consume(text(CHAT, renamed, "привет"));
        bot.consume(text(CHAT, renamed, "/stat_user"));
        assertTrue(telegram.lastText().contains("bob_new"), telegram.lastText());
    }
}
