package com.UserOfTheDayBot;

import com.UserOfTheDayBot.enums.Commands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.UserOfTheDayBot.enums.Games;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendDice;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

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
        assertEquals(Commands.run, Bot.parseCommand("/run", "TestBot").command());
        assertEquals(Commands.run, Bot.parseCommand("  /RUN@testbot extra", "TestBot").command());
        assertEquals("extra", Bot.parseCommand("/stat_user@TestBot  extra ", "TestBot").args());
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

    // ---------------------------------------------------------------------
    // Новые функции
    // ---------------------------------------------------------------------

    private static final long PRIVATE = 555L;   // в личке пользователь — «админ» сам себе

    private void awaitAnnouncements(int count) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            long n = telegram.sentTexts().stream().filter(t -> t.startsWith("\uD83C\uDF89 Сегодня")).count();
            if (n >= count) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Не дождались объявлений: " + telegram.sentTexts());
    }

    @Test
    void slotMachineIsSentRightBeforeAnnouncement() throws Exception {
        bot.consume(text(CHAT, bob, "/reg"));
        bot.consume(text(CHAT, bob, "/run"));
        awaitAnnouncements(1);

        List<Object> calls = telegram.calls;
        int dice = -1;
        for (int i = 0; i < calls.size(); i++) {
            if (calls.get(i) instanceof SendDice d) {
                assertEquals("\uD83C\uDFB0", d.getEmoji());
                dice = i;
            }
        }
        assertTrue(dice >= 0, "🎰 не отправлен");
        SendMessage next = (SendMessage) calls.get(dice + 1);
        assertTrue(next.getText().startsWith("\uD83C\uDF89 Сегодня красавчик дня"), next.getText());
    }

    @Test
    void announcementMentionsStreak() throws Exception {
        bot.consume(text(CHAT, bob, "/reg"));
        UserForBD bobPlayer = db.getListOfPlayers(CHAT).get(0);
        db.saveWinner(CHAT, bobPlayer, LocalDate.of(2026, 9, 27), Games.user_of_the_day);
        db.saveWinner(CHAT, bobPlayer, LocalDate.of(2026, 9, 28), Games.user_of_the_day);

        bot.consume(text(CHAT, bob, "/run"));
        SendMessage announce = telegram.awaitMessage(m -> m.getText().startsWith("\uD83C\uDF89 Сегодня"), WAIT);
        assertTrue(announce.getText().contains("3 дня подряд"), announce.getText());
    }

    @Test
    void statisticForMonthAndYear() throws Exception {
        bot.consume(text(CHAT, bob, "/reg"));
        UserForBD bobPlayer = db.getListOfPlayers(CHAT).get(0);
        db.saveWinner(CHAT, bobPlayer, LocalDate.of(2026, 8, 15), Games.loser_of_the_day);
        db.saveWinner(CHAT, bobPlayer, LocalDate.of(2026, 9, 10), Games.loser_of_the_day);

        bot.consume(text(CHAT, bob, "/stat_loser month"));
        assertTrue(telegram.lastText().contains("сентябрь 2026"), telegram.lastText());
        assertTrue(telegram.lastText().contains("bob - 1 раз(а)"), telegram.lastText());

        bot.consume(text(CHAT, bob, "/stat_loser год"));
        assertTrue(telegram.lastText().contains("за 2026 год"), telegram.lastText());
        assertTrue(telegram.lastText().contains("bob - 2 раз(а)"), telegram.lastText());

        bot.consume(text(CHAT, bob, "/stat_user month"));
        assertTrue(telegram.lastText().contains("Пока никто не выигрывал"), telegram.lastText());

        bot.consume(text(CHAT, bob, "/stat_user week"));
        assertTrue(telegram.lastText().startsWith("Можно так"), telegram.lastText());
    }

    @Test
    void myStatistic() throws Exception {
        bot.consume(text(CHAT, bob, "/me"));
        assertTrue(telegram.lastText().contains("/reg"));

        bot.consume(text(CHAT, bob, "/reg"));
        UserForBD bobPlayer = db.getListOfPlayers(CHAT).get(0);
        db.saveWinner(CHAT, bobPlayer, LocalDate.of(2026, 9, 27), Games.user_of_the_day);
        db.saveWinner(CHAT, bobPlayer, LocalDate.of(2026, 9, 28), Games.user_of_the_day);
        db.saveWinner(CHAT, bobPlayer, LocalDate.of(2026, 8, 1), Games.loser_of_the_day);

        bot.consume(text(CHAT, bob, "/me"));
        String me = telegram.lastText();
        assertTrue(me.contains("Красавчик дня: 2 раз(а), в этом месяце: 2"), me);
        assertTrue(me.contains("Неудачник дня: 1 раз(а), в этом месяце: 0"), me);
        assertTrue(me.contains("Серия красавчика: сейчас 2 дня, рекорд 2 дня"), me);
        assertTrue(me.contains("Серия неудачника: сейчас 0 дней, рекорд 1 день"), me);
    }

    @Test
    void autoDrawRunsBothGamesOnceAfterConfiguredTime() throws Exception {
        bot.consume(text(PRIVATE, bob, "/reg"));
        bot.consume(text(PRIVATE, bob, "/auto 9:30"));
        assertTrue(telegram.lastText().contains("09:30"), telegram.lastText());
        bot.consume(text(PRIVATE, bob, "/auto"));
        assertTrue(telegram.lastText().contains("каждый день в 09:30"), telegram.lastText());

        // 07:00 UTC = 09:00 по Праге — ещё рано
        Bot early = newBot(0, Instant.parse("2026-09-29T07:00:00Z"));
        early.runScheduledDraws();
        early.shutdown();
        assertNull(db.getWinnerOn(PRIVATE, Games.user_of_the_day, LocalDate.of(2026, 9, 29)));

        // 10:00 по Праге — пора
        Bot onTime = newBot(0, Instant.parse("2026-09-29T08:00:00Z"));
        onTime.runScheduledDraws();
        telegram.awaitMessage(m -> m.getText().startsWith("\uD83C\uDF89 Сегодня неудачник"), WAIT);
        onTime.runScheduledDraws();   // повторная проверка в тот же день ничего не делает
        onTime.shutdown();

        assertNotNull(db.getWinnerOn(PRIVATE, Games.user_of_the_day, LocalDate.of(2026, 9, 29)));
        assertNotNull(db.getWinnerOn(PRIVATE, Games.loser_of_the_day, LocalDate.of(2026, 9, 29)));
        assertEquals(2, db.getHistory(PRIVATE, null, 20).size());
        assertEquals(1, telegram.sentTexts().stream().filter(t -> t.startsWith("⏰ Ежедневный")).count(), telegram.sentTexts().toString());
    }

    @Test
    void autoRequiresAdminAndValidTime() {
        bot.consume(text(CHAT, bob, "/auto 10:00"));
        assertEquals("Эта команда только для администраторов чата.", telegram.lastText());
        bot.consume(text(PRIVATE, bob, "/auto 25:99"));
        assertTrue(telegram.lastText().startsWith("Не понял время"), telegram.lastText());
        bot.consume(text(PRIVATE, bob, "/auto off"));
        assertNull(db.getAutoTime(PRIVATE));
    }

    @Test
    void autoDrawIsDisabledWhenBotIsRemoved() {
        db.setAutoTime(CHAT, "10:00");
        User me = user(999, "Bot", "TestBot");
        me.setIsBot(true);
        Message left = new Message();
        left.setChat(new Chat(CHAT, "supergroup"));
        left.setLeftChatMember(me);
        bot.consume(update(left));
        assertNull(db.getAutoTime(CHAT));
    }

    @Test
    void resetAsksForConfirmation() throws Exception {
        bot.consume(text(PRIVATE, bob, "/reg"));
        db.saveWinner(PRIVATE, db.getListOfPlayers(PRIVATE).get(0), LocalDate.of(2026, 9, 28), Games.user_of_the_day);

        bot.consume(text(PRIVATE, bob, "/reset"));
        SendMessage ask = telegram.sentMessages().get(telegram.sentMessages().size() - 1);
        assertNotNull(ask.getReplyMarkup(), "должны быть кнопки");
        assertEquals(1, db.getHistory(PRIVATE, null, 20).size(), "без подтверждения ничего не сбрасывается");

        bot.consume(callback(PRIVATE, bob, "reset:no"));
        assertEquals(1, db.getHistory(PRIVATE, null, 20).size());

        long issuedAt = Instant.parse("2026-09-29T10:00:00Z").getEpochSecond();
        bot.consume(callback(PRIVATE, bob, "reset:yes:" + (issuedAt - 3600)));
        assertEquals(1, db.getHistory(PRIVATE, null, 20).size(), "просроченная кнопка не срабатывает");

        bot.consume(callback(PRIVATE, bob, "reset:yes:" + issuedAt));
        assertTrue(db.getHistory(PRIVATE, null, 20).isEmpty());
        EditMessageText edit = (EditMessageText) telegram.calls.stream()
                .filter(c -> c instanceof EditMessageText).reduce((a, b) -> b).orElseThrow();
        assertTrue(edit.getText().contains("сброшена"), edit.getText());
    }

    @Test
    void resetButtonIgnoresNonAdmins() {
        bot.consume(text(CHAT, bob, "/reg"));
        db.saveWinner(CHAT, db.getListOfPlayers(CHAT).get(0), LocalDate.of(2026, 9, 28), Games.user_of_the_day);

        long issuedAt = Instant.parse("2026-09-29T10:00:00Z").getEpochSecond();
        bot.consume(callback(CHAT, bob, "reset:yes:" + issuedAt));

        assertEquals(1, db.getHistory(CHAT, null, 20).size());
        AnswerCallbackQuery answer = (AnswerCallbackQuery) telegram.calls.get(telegram.calls.size() - 1);
        assertTrue(answer.getShowAlert());
    }
}
