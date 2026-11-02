package ru.azenizzka.telegram.handlers;

import java.util.List;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import ru.azenizzka.configuration.TelegramBotConfiguration;
import ru.azenizzka.entities.Person;
import ru.azenizzka.repositories.PersonRepository;
import ru.azenizzka.telegram.TelegramBot;

@Component
public class AuditLogHandler implements Handler {
  private final TelegramBotConfiguration configuration;
  private final PersonRepository personRepository;

  public AuditLogHandler(
      TelegramBotConfiguration configuration, PersonRepository personRepository) {
    this.configuration = configuration;
    this.personRepository = personRepository;
  }

  @Override
  public List<SendMessage> handle(Update update, Person person) {
    Message message = update.getMessage();
    if (message == null || !message.hasText()) return List.of();

    String text = message.getText().trim();

    if (text.startsWith("/ban")) {
      String[] parts = text.split("\\s+", 4);
      if (parts.length < 3) {
        return List.of(
            new SendMessage(
                configuration.getAuditLogChatId(),
                "Формат: /ban [ID/username] [7d/30d/perm] [Причина]"));
      }
      String target = parts[1];
      String duration = parts[2].toLowerCase();
      String reason = parts.length > 3 ? parts[3] : "Нарушение правил";

      Person banPerson = null;
      if (target.startsWith("@")) {
        banPerson = personRepository.findByUsername(target.substring(1));
      } else {
        banPerson = personRepository.findByChatId(target);
      }

      if (banPerson == null) {
        return List.of(
            new SendMessage(configuration.getAuditLogChatId(), "Пользователь не найден в БД."));
      }

      long banUntil = 0;
      if (duration.equals("7d")) {
        banUntil = System.currentTimeMillis() + 7L * 24 * 3600 * 1000;
      } else if (duration.equals("30d")) {
        banUntil = System.currentTimeMillis() + 30L * 24 * 3600 * 1000;
      } else if (duration.equals("perm")) {
        banUntil = System.currentTimeMillis() + 3650L * 24 * 3600 * 1000;
      } else {
        return List.of(
            new SendMessage(
                configuration.getAuditLogChatId(), "Неизвестное время. Доступно: 7d, 30d, perm"));
      }

      banPerson.setBanned(true);
      banPerson.setBanUntil(banUntil);
      banPerson.setBanReason(reason);
      personRepository.save(banPerson);

      String unbanDate =
          duration.equals("perm")
              ? "Никогда"
              : new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm")
                  .format(new java.util.Date(banUntil));

      try {
        TelegramBot.getInstance()
            .execute(
                new SendMessage(
                    banPerson.getChatId(),
                    "🚫 Вы забанены в боте.\nПричина: " + reason + "\nРазбан: " + unbanDate));
      } catch (Exception e) {
      }

      return List.of(
          new SendMessage(
              configuration.getAuditLogChatId(),
              "✅ Пользователь " + target + " забанен до " + unbanDate));
    }

    if (text.startsWith("/unban")) {
      String[] parts = text.split("\\s+", 2);
      if (parts.length < 2) {
        return List.of(
            new SendMessage(configuration.getAuditLogChatId(), "Формат: /unban [ID/username]"));
      }

      String target = parts[1];
      Person unbanPerson = null;

      if (target.startsWith("@")) {
        unbanPerson = personRepository.findByUsername(target.substring(1));
      } else {
        unbanPerson = personRepository.findByChatId(target);
      }

      if (unbanPerson == null) {
        return List.of(
            new SendMessage(configuration.getAuditLogChatId(), "Пользователь не найден в БД."));
      }

      unbanPerson.setBanned(false);
      unbanPerson.setBanUntil(null);
      unbanPerson.setBanReason(null);
      personRepository.save(unbanPerson);

      try {
        TelegramBot.getInstance()
            .execute(
                new SendMessage(
                    unbanPerson.getChatId(), "✅ Ваш доступ к боту восстановлен администратором."));
      } catch (Exception e) {
      }

      return List.of(
          new SendMessage(
              configuration.getAuditLogChatId(),
              "✅ Пользователь " + target + " досрочно разбанен."));
    }

    if (message.getReplyToMessage() != null) {
      String replyText = message.getReplyToMessage().getText();
      if (replyText != null) {
        java.util.regex.Matcher m =
            java.util.regex.Pattern.compile("\\[`?(\\d{6,})`?\\]").matcher(replyText);
        if (m.find()) {
          String targetId = m.group(1);
          try {
            TelegramBot.getInstance()
                .execute(new SendMessage(targetId, "📩 Ответ от разработчика:\n\n" + text));
            return List.of(
                new SendMessage(
                    configuration.getAuditLogChatId(),
                    "✅ Сообщение отправлено пользователю " + targetId));
          } catch (Exception e) {
            return List.of(
                new SendMessage(
                    configuration.getAuditLogChatId(), "❌ Ошибка отправки: " + e.getMessage()));
          }
        }
      }
    }

    return List.of();
  }
}
