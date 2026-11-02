package ru.azenizzka.services;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import ru.azenizzka.configuration.TelegramBotConfiguration;
import ru.azenizzka.entities.Person;
import ru.azenizzka.telegram.TelegramBot;

@Service
@RequiredArgsConstructor
public class StatisticsService {
  private final PersonService personService;
  private final TelegramBotConfiguration configuration;

  private final List<String> activeUsersList = new ArrayList<>();
  private final AtomicInteger dailyUsersActive = new AtomicInteger(0);
  private final AtomicInteger dailyCommandsCount = new AtomicInteger(0);
  private final List<String> unrecognizedMessages = new ArrayList<>();
  private final Map<String, AtomicInteger> commandUsage = new ConcurrentHashMap<>();

  // Для совместимости, если прошлый sed не сработал
  public void registerActivity(String chatId) {
    if (!activeUsersList.contains(chatId)) {
      activeUsersList.add(chatId);
      dailyUsersActive.incrementAndGet();
    }
    dailyCommandsCount.incrementAndGet();
  }

  public void registerActivity(Person person, String text) {
    if (!activeUsersList.contains(person.getChatId())) {
      activeUsersList.add(person.getChatId());
      dailyUsersActive.incrementAndGet();
    }
    dailyCommandsCount.incrementAndGet();

    if (text != null && !text.isBlank()) {
      String cmd = text.split("\\s+")[0].toLowerCase();
      if (!cmd.startsWith("/") && !cmd.matches(".*\\d{2,3}.*")) {
        if (cmd.equals("сегодня")
            || cmd.equals("завтра")
            || cmd.equals("понедельник")
            || cmd.equals("вторник")
            || cmd.equals("среда")
            || cmd.equals("четверг")
            || cmd.equals("пятница")
            || cmd.equals("суббота")) {
          cmd = "расписание";
        } else if (text.contains("звонк")) {
          cmd = "звонки";
        } else if (text.contains("настройк")) {
          cmd = "настройки";
        } else if (text.contains("бот")) {
          cmd = "о_боте";
        } else {
          cmd = "свободный_текст";
        }
      } else if (cmd.matches(".*\\d{2,3}.*")) {
        cmd = "смена_группы";
      }
      commandUsage.computeIfAbsent(cmd, k -> new AtomicInteger(0)).incrementAndGet();
    }
  }

  // Для совместимости, если прошлый sed в CommandsHandler не сработал
  public void addUnrecognizedMessage(String name, String username, String chatId, String text) {
    Person p = personService.findByChatId(chatId);
    if (p != null && p.getGroupNum() == 0) return;
    unrecognizedMessages.add(
        String.format(
            "%s [%s] {%s} написал \"%s\", что вызвало ошибку 'Неизвестная команда'",
            name, username, chatId, text));
  }

  public void addUnrecognizedMessage(Person person, String name, String text) {
    if (person.getGroupNum() == 0) {
      return;
    }

    String username = person.getUsername() != null ? "@" + person.getUsername() : "нет_юз";
    unrecognizedMessages.add(
        String.format(
            "%s [%s] {%s} написал \"%s\", что вызвало ошибку 'Неизвестная команда' с реакцией перевода в главное меню",
            name, username, person.getChatId(), text));
  }

  public void sendDailyReport() {
    if (TelegramBot.getInstance() == null) return;

    List<Person> allPersons = personService.findAll();
    long totalUsers = allPersons.size();
    long newUsers = allPersons.stream().filter(p -> p.getGroupNum() == 0).count();

    StringBuilder usageStats = new StringBuilder("\n📊 Топ используемых команд:\n");
    commandUsage.entrySet().stream()
        .sorted((e1, e2) -> Integer.compare(e2.getValue().get(), e1.getValue().get()))
        .limit(10)
        .forEach(
            e ->
                usageStats
                    .append("— ")
                    .append(e.getKey())
                    .append(": ")
                    .append(e.getValue().get())
                    .append("\n"));

    SendMessage statsMsg =
        new SendMessage(
            configuration.getAuditLogChatId(),
            String.format(
                "📈 Ежедневная статистика:\n\n"
                    + "Всего пользователей: %d\n"
                    + "Активных за день: %d\n"
                    + "Новых (с группой 0): %d\n"
                    + "Обработано запросов: %d\n%s",
                totalUsers,
                dailyUsersActive.get(),
                newUsers,
                dailyCommandsCount.get(),
                usageStats.toString()));
    statsMsg.enableMarkdown(true);

    try {
      TelegramBot.getInstance().execute(statsMsg);
    } catch (Exception ignored) {
    }

    if (!unrecognizedMessages.isEmpty()) {
      StringBuilder texts = new StringBuilder("📝 Свободные сообщения от пользователей:\n\n");
      for (String m : unrecognizedMessages) {
        if (texts.length() + m.length() > 3900) {
          try {
            TelegramBot.getInstance()
                .execute(new SendMessage(configuration.getAuditLogChatId(), texts.toString()));
          } catch (Exception ignored) {
          }
          texts = new StringBuilder();
          texts.append("📝 Свободные сообщения (продолжение):\n\n");
        }
        texts.append("— ").append(m).append("\n");
      }
      if (texts.length() > 40) {
        try {
          TelegramBot.getInstance()
              .execute(new SendMessage(configuration.getAuditLogChatId(), texts.toString()));
        } catch (Exception ignored) {
        }
      }
    }

    dailyUsersActive.set(0);
    dailyCommandsCount.set(0);
    unrecognizedMessages.clear();
    activeUsersList.clear();
    commandUsage.clear();
  }
}
