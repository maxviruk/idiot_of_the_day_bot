package com.UserOfTheDayBot;

import com.UserOfTheDayBot.enums.Commands;
import com.UserOfTheDayBot.enums.Games;
import com.UserOfTheDayBot.exceptions.ExistedUserException;
import com.UserOfTheDayBot.model.HistoryEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatAdministrators;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Bot implements LongPollingSingleThreadUpdateConsumer {

    private static final Logger log = LoggerFactory.getLogger(Bot.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final TelegramClient telegramClient;
    private final DBHandler dbHandler;
    private final String botUsername;
    private final Clock clock;
    private final long messageDelayMs;
    private final Random random = new Random();

    // Один поток на все отложенные сообщения розыгрышей.
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
                new BotCommand("stat_user", "статистика красавчиков"),
                new BotCommand("stat_loser", "статистика неудачников"),
                new BotCommand("history", "история победителей"),
                new BotCommand("remove", "(админ) удалить игрока — ответом на сообщение"),
                new BotCommand("reset", "(админ) сбросить статистику чата"),
                new BotCommand("help", "справка"));
        try {
            telegramClient.execute(new SetMyCommands(commands));
        } catch (TelegramApiException e) {
            log.warn("Не удалось зарегистрировать список команд", e);
        }
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
        Commands command = parseCommand(message.getText(), botUsername);
        if (command == null) {
            return;
        }

        User from = message.getFrom();
        switch (command) {
            case start, help -> sendMsg(chatId, helpText());
            case reg -> addUserInGame(chatId, from);
            case unreg -> {
                if (dbHandler.unregister(chatId, from.getId())) {
                    sendMsg(chatId, "Ты вышел из игры.");
                } else {
                    sendMsg(chatId, "Тебя и так нет в игре.");
                }
            }
            case run -> runGame(chatId, Games.user_of_the_day);
            case loser -> runGame(chatId, Games.loser_of_the_day);
            case stat_user -> sendStatisticOfTheGame(chatId, Games.user_of_the_day);
            case stat_loser -> sendStatisticOfTheGame(chatId, Games.loser_of_the_day);
            case history -> sendHistory(chatId);
            case remove -> removePlayer(chatId, message);
            case reset -> resetStatistics(chatId, message);
        }
    }

    /**
     * Разбирает текст вида «/command@BotName аргументы».
     * Возвращает null, если это не команда, команда неизвестна или адресована другому боту.
     */
    static Commands parseCommand(String text, String botUsername) {
        text = text.trim();
        if (!text.startsWith("/")) {
            return null;
        }
        String body = text.substring(1);
        int spaceIdx = body.indexOf(' ');
        String commandPart = spaceIdx == -1 ? body : body.substring(0, spaceIdx);
        int atIdx = commandPart.indexOf('@');
        if (atIdx != -1) {
            if (!commandPart.substring(atIdx + 1).equalsIgnoreCase(botUsername)) {
                return null;
            }
            commandPart = commandPart.substring(0, atIdx);
        }
        try {
            return Commands.valueOf(commandPart.toLowerCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // Игровая логика
    // ---------------------------------------------------------------------

    private void runGame(long chatId, Games game) {
        String drawKey = chatId + ":" + game;
        if (drawsInProgress.contains(drawKey)) {
            sendMsg(chatId, "⏳ Розыгрыш уже идёт, дождитесь результата!");
            return;
        }

        String[] messages = game == Games.user_of_the_day ? messagesForUserOfTheDay : messagesForLoserOfTheDay;
        LocalDate today = today();
        UserForBD todaysWinner = dbHandler.getWinnerOn(chatId, game, today);
        if (todaysWinner != null) {
            sendHtml(chatId, Html.escape(messages[0]) + todaysWinner.getMentionHtml());
            return;
        }

        List<UserForBD> usersInGame = dbHandler.getListOfPlayers(chatId);
        if (usersInGame.isEmpty()) {
            sendMsg(chatId, "Нет игроков. Сначала зарегистрируйтесь командой /reg");
            return;
        }

        UserForBD winner = usersInGame.get(random.nextInt(usersInGame.size()));
        // Победителя записываем сразу (чтобы рестарт посреди анимации не дал разыграть день дважды),
        // а объявляем в конце анимации.
        dbHandler.saveWinner(chatId, winner, today, game);
        drawsInProgress.add(drawKey);

        for (int i = 1; i < messages.length; i++) {
            String line = messages[i];
            scheduler.schedule(() -> sendMsg(chatId, line), messageDelayMs * i, TimeUnit.MILLISECONDS);
        }
        scheduler.schedule(() -> {
            try {
                sendHtml(chatId, Html.escape(messages[0]) + winner.getMentionHtml());
            } finally {
                drawsInProgress.remove(drawKey);
            }
        }, messageDelayMs * messages.length, TimeUnit.MILLISECONDS);
    }

    private void addUserInGame(long chatId, User user) {
        try {
            dbHandler.registration(chatId, user);
        } catch (ExistedUserException e) {
            sendMsg(chatId, "Ты уже в игре");
            return;
        }
        sendMsg(chatId, user.getFirstName() + ", ты в игре");
    }

    private void sendStatisticOfTheGame(long chatId, Games game) {
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
            sendMsg(chatId, "Эта команда только для администраторов чата.");
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

    /** issue #5: сброс статистики чата (только админ). */
    private void resetStatistics(long chatId, Message message) {
        if (!isAdmin(chatId, message)) {
            sendMsg(chatId, "Эта команда только для администраторов чата.");
            return;
        }
        dbHandler.resetChatStatistics(chatId);
        sendMsg(chatId, "Статистика чата сброшена. История очищена.");
    }

    private boolean isAdmin(long chatId, Message message) {
        // в личке считаем пользователя "админом" самого себя
        if (chatId > 0) {
            return true;
        }
        // анонимный админ пишет от имени самой группы
        if (message.getSenderChat() != null && message.getSenderChat().getId() == chatId) {
            return true;
        }
        if (message.getFrom() == null) {
            return false;
        }
        long userId = message.getFrom().getId();
        try {
            List<ChatMember> admins = telegramClient.execute(new GetChatAdministrators(String.valueOf(chatId)));
            for (ChatMember cm : admins) {
                if (cm.getUser() != null && userId == cm.getUser().getId()) {
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

    private void send(SendMessage sendMessage) {
        try {
            telegramClient.execute(sendMessage);
        } catch (TelegramApiException e) {
            log.warn("Не удалось отправить сообщение в чат {}", sendMessage.getChatId(), e);
        }
    }

    private String helpText() {
        return "Бот «Красавчик/Неудачник дня». Команды:\n" +
               "/reg — вступить в игру\n" +
               "/unreg — выйти из игры\n" +
               "/run — разыграть красавчика дня\n" +
               "/loser — разыграть неудачника дня\n" +
               "/stat_user — статистика красавчиков\n" +
               "/stat_loser — статистика неудачников\n" +
               "/history — история победителей\n" +
               "/remove — (админ, в ответ на сообщение) удалить игрока\n" +
               "/reset — (админ) сбросить статистику чата";
    }

    /** «Сегодня» в часовом поясе бота (BOT_TIMEZONE), а не сервера. */
    private LocalDate today() {
        return LocalDate.now(clock);
    }
}
