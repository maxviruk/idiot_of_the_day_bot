package com.UserOfTheDayBot;

import com.UserOfTheDayBot.enums.Commands;
import com.UserOfTheDayBot.enums.DBColumns;
import com.UserOfTheDayBot.enums.Games;
import com.UserOfTheDayBot.exceptions.ExistedUserException;
import com.UserOfTheDayBot.model.HistoryEntry;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatAdministrators;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;

public class Bot extends TelegramLongPollingBot {

    private final Config config;
    private final DBHandler dbHandler;
    private final Random random = new Random();
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    public Bot(Config config, DBHandler dbHandler) {
        super(config.botToken);
        this.config = config;
        this.dbHandler = dbHandler;
    }

    // Отложенная отправка сообщений (для эффекта "розыгрыша")
    private class TimerSendingTask extends TimerTask {
        private final long chatId;
        private final String message;

        TimerSendingTask(long chatId, String message) {
            this.chatId = chatId;
            this.message = message;
        }

        @Override
        public void run() {
            sendMsg(chatId, message);
        }
    }

    private final String[] messagesForUserOfTheDay = {
            "\uD83C\uDF89 Сегодня красавчик дня - ",
            "ВНИМАНИЕ \uD83D\uDD25",
            "Ищем красавчика в этом чате",
            "Гадаем на бинарных опционах \uD83D\uDCCA",
            "Анализируем лунный гороскоп \uD83C\uDF16",
            "Лунная призма дай мне силу \uD83D\uDCAB",
            "СЕКТОР ПРИЗ НА БАРАБАНЕ \uD83C\uDFAF"
    };
    private final String[] messagesForLoserOfTheDay = {
            "\uD83C\uDF89 Сегодня неудачник \uD83C\uDF08 дня - ",
            "ВНИМАНИЕ \uD83D\uDD25",
            "ФЕДЕРАЛЬНЫЙ \uD83D\uDD0D РОЗЫСК НЕУДАЧНИКА \uD83D\uDEA8",
            "4 - спутник запущен \uD83D\uDE80",
            "3 - сводки Интерпола проверены \uD83D\uDE93",
            "2 - твои друзья опрошены \uD83D\uDE45",
            "1 - твой профиль в соцсетях проанализирован \uD83D\uDE40"
    };

    @Override
    public void onUpdateReceived(Update update) {
        if (!update.hasMessage()) {
            return;
        }
        Message message = update.getMessage();
        long chatId = message.getChatId();

        // issue #2: кто-то вышел/был удалён из чата -> убираем из игры автоматически
        if (message.getLeftChatMember() != null) {
            User left = message.getLeftChatMember();
            if (dbHandler.unregister(chatId, left.getId())) {
                System.out.println("Авто-удалён вышедший участник: " + left.getId());
            }
            return;
        }

        // обрабатываем только текстовые команды; стикеры/фото/сервисные сообщения игнорим
        if (!message.hasText()) {
            return;
        }
        String text = message.getText().trim();
        if (!text.startsWith("/")) {
            return;
        }

        // /command@BotName arg -> вытаскиваем имя команды
        String body = text.substring(1);
        int spaceIdx = body.indexOf(' ');
        String commandPart = spaceIdx == -1 ? body : body.substring(0, spaceIdx);
        int atIdx = commandPart.indexOf('@');
        if (atIdx != -1) {
            commandPart = commandPart.substring(0, atIdx);
        }

        Commands command;
        try {
            command = Commands.valueOf(commandPart.toLowerCase());
        } catch (IllegalArgumentException e) {
            // неизвестная команда — просто молчим (в оригинале здесь падало)
            return;
        }

        User from = message.getFrom();
        switch (command) {
            case start:
            case help:
                sendMsg(chatId, helpText());
                break;
            case reg:
                addUserInGame(chatId, from);
                break;
            case unreg:
                if (dbHandler.unregister(chatId, from.getId())) {
                    sendMsg(chatId, "Ты вышел из игры.");
                } else {
                    sendMsg(chatId, "Тебя и так нет в игре.");
                }
                break;
            case run:
                runGame(chatId, Games.user_of_the_day);
                break;
            case loser:
                runGame(chatId, Games.loser_of_the_day);
                break;
            case stat_user:
                sendStatisticOfTheGame(chatId, Games.user_of_the_day);
                break;
            case stat_loser:
                sendStatisticOfTheGame(chatId, Games.loser_of_the_day);
                break;
            case history:
                sendHistory(chatId);
                break;
            case remove:
                removePlayer(chatId, from, message);
                break;
            case reset:
                resetStatistics(chatId, from);
                break;
            default:
                break;
        }
    }

    // ---------------------------------------------------------------------
    // Игровая логика
    // ---------------------------------------------------------------------

    private void runGame(long chatId, Games game) {
        List<UserForBD> usersInGame = dbHandler.getListOfPlayers(chatId);
        if (usersInGame.isEmpty()) {
            sendMsg(chatId, "Нет игроков. Сначала зарегистрируйтесь командой /reg");
            return;
        }

        String[] messages;
        switch (game) {
            case user_of_the_day:
                if (dbHandler.isTheSameDayRunning(chatId, getDayOfYear(), DBColumns.user_of_the_day_run_day)) {
                    sendMsg(chatId, messagesForUserOfTheDay[0]
                            + dbHandler.getWinnerOfTheGame(chatId, Games.user_of_the_day));
                    return;
                }
                messages = messagesForUserOfTheDay;
                break;
            case loser_of_the_day:
                if (dbHandler.isTheSameDayRunning(chatId, getDayOfYear(), DBColumns.loser_of_the_day_run_day)) {
                    sendMsg(chatId, messagesForLoserOfTheDay[0]
                            + dbHandler.getWinnerOfTheGame(chatId, Games.loser_of_the_day));
                    return;
                }
                messages = messagesForLoserOfTheDay;
                break;
            default:
                return;
        }

        Timer timer = new Timer();
        final int MESSAGE_DELAY = 1500;
        for (int i = 1; i < messages.length; i++) {
            timer.schedule(new TimerSendingTask(chatId, messages[i]), (long) MESSAGE_DELAY * i);
        }
        UserForBD winner = usersInGame.get(random.nextInt(usersInGame.size()));
        timer.schedule(new TimerSendingTask(chatId, messages[0] + winner.getNotificationName()),
                (long) MESSAGE_DELAY * messages.length);

        dbHandler.setWinnerAndDayRunning(chatId, winner, getDayOfYear(), game);
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
            sb = new StringBuilder("\uD83C\uDF89 Результаты Красавчик Дня\n");
            players.sort((a, b) -> b.getUserDayCounter() - a.getUserDayCounter());
            for (UserForBD u : players) {
                sb.append(i++).append(") ").append(u.getNotificationName())
                  .append(" - ").append(u.getUserDayCounter()).append(" раз(а)\n");
            }
        } else {
            sb = new StringBuilder("Результаты \uD83C\uDF08 Неудачника Дня\n");
            players.sort((a, b) -> b.getLoserDayCounter() - a.getLoserDayCounter());
            for (UserForBD u : players) {
                sb.append(i++).append(") ").append(u.getNotificationName())
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
        StringBuilder sb = new StringBuilder("\uD83D\uDCDC История (последние 20):\n");
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
    private void removePlayer(long chatId, User requester, Message message) {
        if (!isAdmin(chatId, requester.getId())) {
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
    private void resetStatistics(long chatId, User requester) {
        if (!isAdmin(chatId, requester.getId())) {
            sendMsg(chatId, "Эта команда только для администраторов чата.");
            return;
        }
        dbHandler.resetChatStatistics(chatId);
        sendMsg(chatId, "Статистика чата сброшена. История очищена.");
    }

    private boolean isAdmin(long chatId, long userId) {
        // в личке считаем пользователя "админом" самого себя
        if (chatId > 0) {
            return true;
        }
        try {
            GetChatAdministrators g = new GetChatAdministrators();
            g.setChatId(String.valueOf(chatId));
            List<ChatMember> admins = execute(g);
            for (ChatMember cm : admins) {
                if (cm.getUser() != null && userId == cm.getUser().getId()) {
                    return true;
                }
            }
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
        return false;
    }

    // ---------------------------------------------------------------------
    // Вспомогательное
    // ---------------------------------------------------------------------

    private synchronized void sendMsg(long chatId, String text) {
        SendMessage sendMessage = SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text(text)
                .build();
        try {
            execute(sendMessage);
        } catch (TelegramApiException e) {
            e.printStackTrace();
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

    // Используем день года (1..366): уникален в пределах года и не путается,
    // в отличие от оригинального формата "DD" (там был баг: день года вместо дня месяца).
    private int getDayOfYear() {
        return LocalDate.now(config.zoneId).getDayOfYear();
    }

    @Override
    public String getBotUsername() {
        return config.botUsername;
    }
}
