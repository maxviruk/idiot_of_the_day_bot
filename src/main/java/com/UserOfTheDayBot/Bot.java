package com.UserOfTheDayBot;

import com.UserOfTheDayBot.enums.Commands;
import com.UserOfTheDayBot.enums.Games;
import com.UserOfTheDayBot.exceptions.ExistedUserException;
import com.UserOfTheDayBot.model.HistoryEntry;
import com.UserOfTheDayBot.model.WinCount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatAdministrators;
import org.telegram.telegrambots.meta.api.methods.send.SendDice;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.message.MaybeInaccessibleMessage;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.io.Serializable;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Bot implements LongPollingSingleThreadUpdateConsumer {

    private static final Logger log = LoggerFactory.getLogger(Bot.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter TIME_INPUT_FMT = DateTimeFormatter.ofPattern("H:mm");
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("LLLL yyyy", Locale.of("ru"));

    private static final String SLOT_MACHINE = "🎰";
    private static final String RESET_YES = "reset:yes:";
    private static final String RESET_NO = "reset:no";
    /** Сколько секунд действуют кнопки подтверждения /reset. */
    private static final long RESET_CONFIRM_TTL_SEC = 300;
    private static final String NOT_ADMIN = "Эта команда только для администраторов чата.";

    private final TelegramClient telegramClient;
    private final DBHandler dbHandler;
    private final String botUsername;
    private final Clock clock;
    private final long messageDelayMs;
    private final Random random = new Random();

    // Один поток на отложенные сообщения розыгрышей и проверку авторозыгрышей.
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "draw-announcer");
        t.setDaemon(true);
        return t;
    });

    // Розыгрыши, которые сейчас «крутятся» (ключ chatId:game). Пока розыгрыш идёт,
    // повторный /run не выдаёт победителя раньше объявления.
    private final Set<String> drawsInProgress = ConcurrentHashMap.newKeySet();

    /**
     * @param clock          часы в часовом поясе бота — по ним определяется «сегодня»
     * @param messageDelayMs пауза между сообщениями анимации розыгрыша
     */
    public Bot(TelegramClient telegramClient, DBHandler dbHandler, String botUsername,
               Clock clock, long messageDelayMs) {
        this.telegramClient = telegramClient;
        this.dbHandler = dbHandler;
        this.botUsername = botUsername;
        this.clock = clock;
        this.messageDelayMs = messageDelayMs;
    }

    private final String[] messagesForUserOfTheDay = {
            "🎉 Сегодня красавчик дня - ",
            "ВНИМАНИЕ 🔥",
            "Ищем красавчика в этом чате",
            "Гадаем на бинарных опционах 📊",
            "Анализируем лунный гороскоп 🌖",
            "Лунная призма дай мне силу 💫",
            "СЕКТОР ПРИЗ НА БАРАБАНЕ 🎯"
    };
    private final String[] messagesForLoserOfTheDay = {
            "🎉 Сегодня неудачник 🌈 дня - ",
            "ВНИМАНИЕ 🔥",
            "ФЕДЕРАЛЬНЫЙ 🔍 РОЗЫСК НЕУДАЧНИКА 🚨",
            "4 - спутник запущен 🚀",
            "3 - сводки Интерпола проверены 🚓",
            "2 - твои друзья опрошены 🙅",
            "1 - твой профиль в соцсетях проанализирован 🙀"
    };

    /** Регистрирует список команд, чтобы Telegram подсказывал их при вводе «/». */
    public void registerCommands() {
        List<BotCommand> commands = List.of(
                new BotCommand("reg", "вступить в игру"),
                new BotCommand("unreg", "выйти из игры"),
                new BotCommand("run", "разыграть красавчика дня"),
                new BotCommand("loser", "разыграть неудачника дня"),
                new BotCommand("stat_user", "статистика красавчиков (month / year — за месяц / год)"),
                new BotCommand("stat_loser", "статистика неудачников (month / year — за месяц / год)"),
                new BotCommand("me", "моя статистика и серии побед"),
                new BotCommand("history", "история победителей"),
                new BotCommand("auto", "(админ) авторозыгрыш: /auto 10:00 или /auto off"),
                new BotCommand("remove", "(админ) удалить игрока — ответом на сообщение"),
                new BotCommand("reset", "(админ) сбросить статистику чата"),
                new BotCommand("help", "справка"));
        try {
            telegramClient.execute(new SetMyCommands(commands));
        } catch (TelegramApiException e) {
            log.warn("Не удалось зарегистрировать список команд", e);
        }
    }

    /** Запускает ежеминутную (раз в 30 секунд) проверку авторозыгрышей. */
    public void startAutoDraws() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                runScheduledDraws();
            } catch (RuntimeException e) {
                // исключение в scheduleAtFixedRate отменило бы все следующие запуски
                log.error("Ошибка авторозыгрыша", e);
            }
        }, 10, 30, TimeUnit.SECONDS);
    }

    /** Останавливает поток анимаций (вызывается при завершении приложения). */
    public void shutdown() {
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void consume(Update update) {
        try {
            handleUpdate(update);
        } catch (RuntimeException e) {
            // одно сломанное сообщение не должно останавливать обработку остальных
            log.error("Ошибка при обработке update {}", update.getUpdateId(), e);
        }
    }

    private void handleUpdate(Update update) {
        if (update.hasCallbackQuery()) {
            handleCallback(update.getCallbackQuery());
            return;
        }
        if (!update.hasMessage()) {
            return;
        }
        Message message = update.getMessage();
        long chatId = message.getChatId();

        // Группа стала супергруппой -> у чата новый id, переносим туда статистику.
        // Telegram присылает два сервисных сообщения (в старый и в новый чат) — обрабатываем оба,
        // перенос идемпотентный.
        if (message.getMigrateToChatId() != null) {
            dbHandler.migrateChat(chatId, message.getMigrateToChatId());
            return;
        }
        if (message.getMigrateFromChatId() != null) {
            dbHandler.migrateChat(message.getMigrateFromChatId(), chatId);
            return;
        }

        // issue #2: кто-то вышел/был удалён из чата -> убираем из игры автоматически
        if (message.getLeftChatMember() != null) {
            User left = message.getLeftChatMember();
            if (Boolean.TRUE.equals(left.getIsBot()) && botUsername.equalsIgnoreCase(left.getUserName())) {
                // бота удалили из чата — авторозыгрыш там больше не нужен
                dbHandler.setAutoTime(chatId, null);
                return;
            }
            if (dbHandler.unregister(chatId, left.getId())) {
                log.info("Авто-удалён вышедший участник {} из чата {}", left.getId(), chatId);
            }
            return;
        }

        // держим username / имя в актуальном состоянии
        if (message.getFrom() != null) {
            dbHandler.refreshUser(message.getFrom());
        }

        // обрабатываем только текстовые команды; стикеры/фото/сервисные сообщения игнорим
        if (!message.hasText()) {
            return;
        }
        ParsedCommand parsed = parseCommand(message.getText(), botUsername);
        if (parsed == null) {
            return;
        }

        User from = message.getFrom();
        switch (parsed.command()) {
            case start, help -> sendMsg(chatId, helpText());
            case reg -> addUserInGame(chatId, from);
            case unreg -> {
                if (dbHandler.unregister(chatId, from.getId())) {
                    sendMsg(chatId, "Ты вышел из игры. Статистика сохранится, если вернёшься через /reg.");
                } else {
                    sendMsg(chatId, "Тебя и так нет в игре.");
                }
            }
            case run -> runGame(chatId, Games.user_of_the_day);
            case loser -> runGame(chatId, Games.loser_of_the_day);
            case stat_user -> sendStatistic(chatId, Games.user_of_the_day, parsed.args());
            case stat_loser -> sendStatistic(chatId, Games.loser_of_the_day, parsed.args());
            case me -> sendMyStatistic(chatId, from);
            case history -> sendHistory(chatId);
            case auto -> configureAutoDraw(chatId, message, parsed.args());
            case remove -> removePlayer(chatId, message);
            case reset -> askResetConfirmation(chatId, message);
        }
    }

    /** Команда и всё, что написано после неё. */
    record ParsedCommand(Commands command, String args) {
    }

    /**
     * Разбирает текст вида «/command@BotName аргументы».
     * Возвращает null, если это не команда, команда неизвестна или адресована другому боту.
     */
    static ParsedCommand parseCommand(String text, String botUsername) {
        text = text.trim();
        if (!text.startsWith("/")) {
            return null;
        }
        String body = text.substring(1);
        int spaceIdx = body.indexOf(' ');
        String commandPart = spaceIdx == -1 ? body : body.substring(0, spaceIdx);
        String args = spaceIdx == -1 ? "" : body.substring(spaceIdx + 1).trim();
        int atIdx = commandPart.indexOf('@');
        if (atIdx != -1) {
            if (!commandPart.substring(atIdx + 1).equalsIgnoreCase(botUsername)) {
                return null;
            }
            commandPart = commandPart.substring(0, atIdx);
        }
        try {
            return new ParsedCommand(Commands.valueOf(commandPart.toLowerCase()), args);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // Розыгрыш
    // ---------------------------------------------------------------------

    private synchronized void runGame(long chatId, Games game) {
        if (drawsInProgress.contains(drawKey(chatId, game))) {
            sendMsg(chatId, "⏳ Розыгрыш уже идёт, дождитесь результата!");
            return;
        }
        LocalDate today = today();
        UserForBD todaysWinner = dbHandler.getWinnerOn(chatId, game, today);
        if (todaysWinner != null) {
            sendHtml(chatId, announcement(chatId, game, todaysWinner, today));
            return;
        }
        List<UserForBD> usersInGame = dbHandler.getListOfPlayers(chatId);
        if (usersInGame.isEmpty()) {
            sendMsg(chatId, "Нет игроков. Сначала зарегистрируйтесь командой /reg");
            return;
        }
        startDraw(chatId, game, usersInGame, 0);
    }

    /**
     * Выбирает победителя, сразу записывает его в базу (чтобы рестарт посреди анимации
     * не дал разыграть день дважды) и запускает анимацию: фразы, 🎰, объявление.
     *
     * @return длительность анимации в мс
     */
    private long startDraw(long chatId, Games game, List<UserForBD> players, long startDelayMs) {
        String drawKey = drawKey(chatId, game);
        LocalDate today = today();
        UserForBD winner = players.get(random.nextInt(players.size()));
        dbHandler.saveWinner(chatId, winner, today, game);
        drawsInProgress.add(drawKey);
        String announcement = announcement(chatId, game, winner, today);

        String[] messages = messages(game);
        long at = startDelayMs;
        for (int i = 1; i < messages.length; i++) {
            at += messageDelayMs;
            String line = messages[i];
            scheduler.schedule(() -> sendMsg(chatId, line), at, TimeUnit.MILLISECONDS);
        }
        at += messageDelayMs;
        scheduler.schedule(() -> sendSlotMachine(chatId), at, TimeUnit.MILLISECONDS);
        at += 2 * messageDelayMs;   // даём барабану докрутиться
        scheduler.schedule(() -> {
            try {
                sendHtml(chatId, announcement);
            } finally {
                drawsInProgress.remove(drawKey);
            }
        }, at, TimeUnit.MILLISECONDS);
        return at - startDelayMs;
    }

    /** «🎉 Сегодня красавчик дня - @user» + серия, если она больше одного дня. */
    private String announcement(long chatId, Games game, UserForBD winner, LocalDate today) {
        String text = Html.escape(messages(game)[0]) + winner.getMentionHtml();
        int streak = Streaks.of(dbHandler.getTimeline(chatId, game), winner.getId(), today).current();
        if (streak >= 2) {
            text += "\n🔥 " + Streaks.days(streak) + " подряд!";
        }
        return text;
    }

    private String[] messages(Games game) {
        return game == Games.user_of_the_day ? messagesForUserOfTheDay : messagesForLoserOfTheDay;
    }

    private static String drawKey(long chatId, Games game) {
        return chatId + ":" + game;
    }

    // ---------------------------------------------------------------------
    // Авторозыгрыш по расписанию
    // ---------------------------------------------------------------------

    /** Запускает розыгрыши во всех чатах, где наступило время авторозыгрыша, а сегодня ещё не играли. */
    void runScheduledDraws() {
        String now = LocalTime.now(clock).format(TIME_FMT);
        for (long chatId : dbHandler.getChatsWithAutoTimeReached(now)) {
            try {
                autoDraw(chatId);
            } catch (RuntimeException e) {
                log.error("Ошибка авторозыгрыша в чате {}", chatId, e);
            }
        }
    }

    private synchronized void autoDraw(long chatId) {
        List<UserForBD> players = dbHandler.getListOfPlayers(chatId);
        if (players.isEmpty()) {
            return;
        }
        LocalDate today = today();
        long offset = 0;
        boolean started = false;
        for (Games game : Games.values()) {
            if (drawsInProgress.contains(drawKey(chatId, game))
                    || dbHandler.getWinnerOn(chatId, game, today) != null) {
                continue;
            }
            if (!started) {
                sendMsg(chatId, "⏰ Ежедневный авторозыгрыш!");
                started = true;
            }
            // игры идут друг за другом, а не вперемешку
            offset += startDraw(chatId, game, players, offset) + 2 * messageDelayMs;
        }
    }

    private void configureAutoDraw(long chatId, Message message, String args) {
        String zone = clock.getZone().getId();
        if (args.isBlank()) {
            String time = dbHandler.getAutoTime(chatId);
            sendMsg(chatId, time == null
                    ? "Авторозыгрыш выключен. Включить: /auto 10:00"
                    : "⏰ Авторозыгрыш каждый день в " + time + " (" + zone + "). Выключить: /auto off");
            return;
        }
        if (!isAdmin(chatId, message)) {
            sendMsg(chatId, NOT_ADMIN);
            return;
        }
        String arg = args.toLowerCase();
        if (arg.equals("off") || arg.equals("выкл") || arg.equals("stop")) {
            dbHandler.setAutoTime(chatId, null);
            sendMsg(chatId, "Авторозыгрыш выключен.");
            return;
        }
        LocalTime time;
        try {
            time = LocalTime.parse(arg, TIME_INPUT_FMT);
        } catch (DateTimeParseException e) {
            sendMsg(chatId, "Не понял время. Пример: /auto 10:00 (или /auto off, чтобы выключить)");
            return;
        }
        String formatted = time.format(TIME_FMT);
        dbHandler.setAutoTime(chatId, formatted);
        sendMsg(chatId, "⏰ Готово! Каждый день в " + formatted + " (" + zone + ") я сам разыграю " +
                        "красавчика и неудачника дня, если их ещё не выбрали вручную.");
    }

    // ---------------------------------------------------------------------
    // Регистрация и статистика
    // ---------------------------------------------------------------------

    private void addUserInGame(long chatId, User user) {
        try {
            dbHandler.registration(chatId, user);
        } catch (ExistedUserException e) {
            sendMsg(chatId, "Ты уже в игре");
            return;
        }
        sendMsg(chatId, user.getFirstName() + ", ты в игре");
    }

    /** /stat_user и /stat_loser: за всё время, либо за месяц / год (аргумент). */
    private void sendStatistic(long chatId, Games game, String args) {
        String period = args.toLowerCase();
        LocalDate today = today();
        LocalDate from;
        String periodTitle;
        switch (period) {
            case "" -> {
                sendAllTimeStatistic(chatId, game);
                return;
            }
            case "month", "m", "месяц" -> {
                from = today.withDayOfMonth(1);
                periodTitle = "за " + today.format(MONTH_FMT);
            }
            case "year", "y", "год" -> {
                from = today.withDayOfYear(1);
                periodTitle = "за " + today.getYear() + " год";
            }
            default -> {
                sendMsg(chatId, "Можно так: /" + commandName(game) + ", /" + commandName(game) +
                                " month или /" + commandName(game) + " year");
                return;
            }
        }
        List<WinCount> counts = dbHandler.getWinCounts(chatId, game, from);
        String title = game == Games.user_of_the_day
                ? "🎉 Красавчики дня " + periodTitle
                : "🌈 Неудачники дня " + periodTitle;
        if (counts.isEmpty()) {
            sendMsg(chatId, title + "\nПока никто не выигрывал.");
            return;
        }
        StringBuilder sb = new StringBuilder(title).append('\n');
        int i = 1;
        for (WinCount c : counts) {
            sb.append(i++).append(") ").append(c.name()).append(" - ").append(c.count()).append(" раз(а)\n");
        }
        sendMsg(chatId, sb.toString());
    }

    private void sendAllTimeStatistic(long chatId, Games game) {
        List<UserForBD> players = dbHandler.getListOfPlayers(chatId);
        if (players.isEmpty()) {
            sendMsg(chatId, "Нет игроков.");
            return;
        }
        StringBuilder sb;
        int i = 1;
        if (game == Games.user_of_the_day) {
            sb = new StringBuilder("🎉 Результаты Красавчик Дня\n");
            players.sort((a, b) -> b.getUserDayCounter() - a.getUserDayCounter());
            for (UserForBD u : players) {
                sb.append(i++).append(") ").append(u.getDisplayName())
                  .append(" - ").append(u.getUserDayCounter()).append(" раз(а)\n");
            }
        } else {
            sb = new StringBuilder("Результаты 🌈 Неудачника Дня\n");
            players.sort((a, b) -> b.getLoserDayCounter() - a.getLoserDayCounter());
            for (UserForBD u : players) {
                sb.append(i++).append(") ").append(u.getDisplayName())
                  .append(" - ").append(u.getLoserDayCounter()).append(" раз(а)\n");
            }
        }
        sendMsg(chatId, sb.toString());
    }

    /** /me — личная статистика: всего, за месяц, текущая и рекордная серии. */
    private void sendMyStatistic(long chatId, User from) {
        Optional<UserForBD> player = dbHandler.findPlayer(chatId, from.getId());
        if (player.isEmpty()) {
            sendMsg(chatId, "Ты ещё не в игре. Вступай: /reg");
            return;
        }
        UserForBD me = player.get();
        LocalDate today = today();
        LocalDate monthStart = today.withDayOfMonth(1);

        StringBuilder sb = new StringBuilder("📊 Статистика: ").append(me.getDisplayName()).append('\n');
        sb.append("🎉 Красавчик дня: ").append(me.getUserDayCounter()).append(" раз(а), в этом месяце: ")
          .append(monthWins(chatId, Games.user_of_the_day, me.getId(), monthStart)).append('\n');
        sb.append("🌈 Неудачник дня: ").append(me.getLoserDayCounter()).append(" раз(а), в этом месяце: ")
          .append(monthWins(chatId, Games.loser_of_the_day, me.getId(), monthStart)).append('\n');
        appendStreak(sb, "красавчика", Streaks.of(dbHandler.getTimeline(chatId, Games.user_of_the_day), me.getId(), today));
        appendStreak(sb, "неудачника", Streaks.of(dbHandler.getTimeline(chatId, Games.loser_of_the_day), me.getId(), today));
        if (!dbHandler.isRegistered(chatId, me.getId())) {
            sb.append("Сейчас ты не в игре — вернуться: /reg");
        }
        sendMsg(chatId, sb.toString().trim());
    }

    private int monthWins(long chatId, Games game, long userId, LocalDate monthStart) {
        return dbHandler.getWinCounts(chatId, game, monthStart).stream()
                .filter(c -> c.userId() == userId)
                .mapToInt(WinCount::count)
                .findFirst()
                .orElse(0);
    }

    private static void appendStreak(StringBuilder sb, String gameName, Streaks.Streak streak) {
        sb.append("🔥 Серия ").append(gameName).append(": сейчас ").append(Streaks.days(streak.current()))
          .append(", рекорд ").append(Streaks.days(streak.best())).append('\n');
    }

    /** История победителей (issue: подгрузка истории). */
    private void sendHistory(long chatId) {
        List<HistoryEntry> entries = dbHandler.getHistory(chatId, null, 20);
        if (entries.isEmpty()) {
            sendMsg(chatId, "История пуста. Сыграйте /run или /loser.");
            return;
        }
        StringBuilder sb = new StringBuilder("📜 История (последние 20):\n");
        for (HistoryEntry e : entries) {
            String label = e.game.equals(Games.user_of_the_day.name()) ? "красавчик" : "неудачник";
            sb.append(e.date.format(DATE_FMT)).append(" — ")
              .append(label).append(": ").append(e.winnerName).append('\n');
        }
        sendMsg(chatId, sb.toString());
    }

    // ---------------------------------------------------------------------
    // Админские команды
    // ---------------------------------------------------------------------

    /** issue #2: админ отвечает (reply) на сообщение игрока и пишет /remove. */
    private void removePlayer(long chatId, Message message) {
        if (!isAdmin(chatId, message)) {
            sendMsg(chatId, NOT_ADMIN);
            return;
        }
        Message reply = message.getReplyToMessage();
        if (reply == null || reply.getFrom() == null) {
            sendMsg(chatId, "Чтобы удалить игрока, ответьте этой командой на его сообщение: /remove");
            return;
        }
        User target = reply.getFrom();
        if (dbHandler.unregister(chatId, target.getId())) {
            sendMsg(chatId, target.getFirstName() + " удалён(а) из игры.");
        } else {
            sendMsg(chatId, "Этого игрока нет в игре.");
        }
    }

    /** issue #5: сброс статистики чата — только админ и только после подтверждения кнопкой. */
    private void askResetConfirmation(long chatId, Message message) {
        if (!isAdmin(chatId, message)) {
            sendMsg(chatId, NOT_ADMIN);
            return;
        }
        long issuedAt = clock.instant().getEpochSecond();
        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup(List.of(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("✅ Да, сбросить").callbackData(RESET_YES + issuedAt).build(),
                InlineKeyboardButton.builder().text("❌ Отмена").callbackData(RESET_NO).build())));
        send(SendMessage.builder()
                .chatId(chatId)
                .text("⚠️ Точно сбросить всю статистику и историю этого чата? Это необратимо.")
                .replyMarkup(keyboard)
                .build());
    }

    private void handleCallback(CallbackQuery query) {
        String data = query.getData();
        MaybeInaccessibleMessage message = query.getMessage();
        if (data == null || message == null || !(data.equals(RESET_NO) || data.startsWith(RESET_YES))) {
            answerCallback(query, null, false);
            return;
        }
        long chatId = message.getChatId();
        Integer messageId = message.getMessageId();
        if (!isAdmin(chatId, query.getFrom().getId(), null)) {
            answerCallback(query, "Подтвердить может только администратор чата.", true);
            return;
        }
        if (data.equals(RESET_NO)) {
            editMessage(chatId, messageId, "Сброс статистики отменён.");
            answerCallback(query, null, false);
            return;
        }
        long issuedAt;
        try {
            issuedAt = Long.parseLong(data.substring(RESET_YES.length()));
        } catch (NumberFormatException e) {
            answerCallback(query, null, false);
            return;
        }
        if (clock.instant().getEpochSecond() - issuedAt > RESET_CONFIRM_TTL_SEC) {
            editMessage(chatId, messageId, "Время на подтверждение истекло. Повторите /reset.");
            answerCallback(query, null, false);
            return;
        }
        dbHandler.resetChatStatistics(chatId);
        editMessage(chatId, messageId, "🧹 Статистика чата сброшена. История очищена.");
        answerCallback(query, "Готово", false);
    }

    private boolean isAdmin(long chatId, Message message) {
        return isAdmin(chatId, message.getFrom() == null ? null : message.getFrom().getId(), message.getSenderChat());
    }

    private boolean isAdmin(long chatId, Long userId, Chat senderChat) {
        // в личке считаем пользователя "админом" самого себя
        if (chatId > 0) {
            return true;
        }
        // анонимный админ пишет от имени самой группы
        if (senderChat != null && senderChat.getId() == chatId) {
            return true;
        }
        if (userId == null) {
            return false;
        }
        try {
            List<ChatMember> admins = telegramClient.execute(new GetChatAdministrators(String.valueOf(chatId)));
            for (ChatMember cm : admins) {
                if (cm.getUser() != null && userId.equals(cm.getUser().getId())) {
                    return true;
                }
            }
        } catch (TelegramApiException e) {
            log.warn("Не удалось получить список админов чата {}", chatId, e);
        }
        return false;
    }

    // ---------------------------------------------------------------------
    // Вспомогательное
    // ---------------------------------------------------------------------

    private void sendMsg(long chatId, String text) {
        send(SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .build());
    }

    /** Сообщение с HTML-разметкой: всё динамическое должно быть экранировано через {@link Html}. */
    private void sendHtml(long chatId, String html) {
        send(SendMessage.builder()
                .chatId(chatId)
                .text(html)
                .parseMode("HTML")
                .build());
    }

    private void sendSlotMachine(long chatId) {
        send(SendDice.builder().chatId(chatId).emoji(SLOT_MACHINE).build());
    }

    private void editMessage(long chatId, Integer messageId, String text) {
        send(EditMessageText.builder().chatId(chatId).messageId(messageId).text(text).build());
    }

    private void answerCallback(CallbackQuery query, String text, boolean alert) {
        send(AnswerCallbackQuery.builder()
                .callbackQueryId(query.getId())
                .text(text)
                .showAlert(alert)
                .build());
    }

    private void send(BotApiMethod<? extends Serializable> method) {
        try {
            telegramClient.execute(method);
        } catch (TelegramApiException e) {
            log.warn("Не удалось выполнить {}", method.getMethod(), e);
        }
    }

    private static String commandName(Games game) {
        return game == Games.user_of_the_day ? Commands.stat_user.name() : Commands.stat_loser.name();
    }

    private String helpText() {
        return "Бот «Красавчик/Неудачник дня». Команды:\n" +
               "/reg — вступить в игру\n" +
               "/unreg — выйти из игры\n" +
               "/run — разыграть красавчика дня\n" +
               "/loser — разыграть неудачника дня\n" +
               "/stat_user — статистика красавчиков (/stat_user month — за месяц, year — за год)\n" +
               "/stat_loser — статистика неудачников (так же: month / year)\n" +
               "/me — моя статистика и серии побед\n" +
               "/history — история победителей\n" +
               "/auto 10:00 — (админ) авторозыгрыш каждый день, /auto off — выключить\n" +
               "/remove — (админ, в ответ на сообщение) удалить игрока\n" +
               "/reset — (админ) сбросить статистику чата";
    }

    /** «Сегодня» в часовом поясе бота (BOT_TIMEZONE), а не сервера. */
    private LocalDate today() {
        return LocalDate.now(clock);
    }
}
