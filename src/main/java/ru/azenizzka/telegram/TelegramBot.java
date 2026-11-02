package ru.azenizzka.telegram;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatAdministrators;
import org.telegram.telegrambots.meta.api.methods.pinnedmessages.PinChatMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import ru.azenizzka.configuration.TelegramBotConfiguration;
import ru.azenizzka.entities.Person;
import ru.azenizzka.repositories.PersonRepository;
import ru.azenizzka.telegram.handlers.InputType;
import ru.azenizzka.telegram.handlers.MasterHandler;

@Component
@Slf4j
public class TelegramBot extends TelegramLongPollingBot {
  private final java.util.concurrent.atomic.AtomicBoolean isBroadcasting = new java.util.concurrent.atomic.AtomicBoolean(false);
  private static final long RATE_LIMIT_DELAY_MS = 55L;

  private final PersonRepository personRepository;
  private final TelegramBotConfiguration configuration;
  private final MasterHandler masterHandler;
  private static TelegramBot instance;

  public TelegramBot(
      PersonRepository personRepository,
      TelegramBotConfiguration configuration,
      MasterHandler masterHandler) {
    super(configuration.getToken());
    this.personRepository = personRepository;
    this.configuration = configuration;
    this.masterHandler = masterHandler;
    instance = this;
  }

  public static TelegramBot getInstance() {
    return instance;
  }

  @Override
  public String getBotUsername() {
    return configuration.getName();
  }

  @Override
  public void onUpdateReceived(Update update) {
    String chatId;
    String username;

    if (update.hasCallbackQuery() && update.getCallbackQuery().getMessage() != null) {
      chatId = update.getCallbackQuery().getMessage().getChatId().toString();
      username = update.getCallbackQuery().getFrom().getUserName();
    } else if (update.hasMessage() && update.getMessage().hasText()) {
      chatId = update.getMessage().getChatId().toString();
      username =
          update.getMessage().getChat().isUserChat()
              ? update.getMessage().getChat().getUserName()
              : update.getMessage().getChat().getTitle();
    } else {
      return;
    }

    try {
      Person person = personRepository.findByChatId(chatId);
      if (person == null) {
        person = new Person();
        person.setChatId(chatId);
        person.setUsername(username);
        person.setInputType(InputType.COMMAND);
        person.setNotificationsEnabled(false);
      } else if (username != null) {
        person.setUsername(username);
      }

      if ((configuration.getAdminChatId() != null
              && !configuration.getAdminChatId().isBlank()
              && configuration.getAdminChatId().equals(chatId))
          || (configuration.getAuditLogChatId() != null
              && configuration.getAuditLogChatId().equals(chatId))) {
        person.setAdmin(true);
      }

      List<SendMessage> responses = masterHandler.handle(update, person);

      if (!person.isBanned()) {
        personRepository.save(person);
      }

      sendMessage(responses);
    } catch (Exception e) {
      log.error("Ошибка при обработке события от chatId {}: {}", chatId, e.getMessage(), e);
    }
  }

  public boolean isGroupAdmin(String chatId, Long userId) {
    if (!chatId.startsWith("-")) {
      return true;
    }
    try {
      List<ChatMember> admins = execute(new GetChatAdministrators(chatId));
      for (ChatMember member : admins) {
        if (member.getUser().getId().equals(userId)) {
          return true;
        }
      }
    } catch (TelegramApiException e) {
      log.warn("Не удалось проверить права админа в чате {}: {}", chatId, e.getMessage());
    }
    return false;
  }

  private boolean isDeadUserError(TelegramApiException e) {
    String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
    if (e instanceof TelegramApiRequestException reqEx) {
      int code = reqEx.getErrorCode();
      String apiResp = reqEx.getApiResponse() != null ? reqEx.getApiResponse().toLowerCase() : "";
      if (code == 403) {
        return true;
      }
      if (code == 400
          && (apiResp.contains("chat not found")
              || apiResp.contains("user is deactivated")
              || apiResp.contains("have no rights to send a message"))) {
        return true;
      }
    }
    return msg.contains("blocked")
        || msg.contains("deactivated")
        || msg.contains("chat not found")
        || msg.contains("kicked")
        || msg.contains("forbidden");
  }

  private void cleanupDeadPerson(String chatId, String reason) {
    try {
      Person dead = personRepository.findByChatId(chatId);
      if (dead != null
          && !chatId.equals(configuration.getAuditLogChatId())
          && !chatId.equals(configuration.getAdminChatId())) {
        personRepository.delete(dead);
        log.info("Удален неактивный/заблокировавший чат {} из БД ({})", chatId, reason);
      }
    } catch (Exception ex) {
      log.warn("Не удалось удалить чат {} из БД: {}", chatId, ex.getMessage());
    }
  }

  public void sendAndPinSchedule(Person groupPerson, String text) {
    String chatId = groupPerson.getChatId();

    if (groupPerson.getPinnedMessageId() != null) {
      try {
        execute(new DeleteMessage(chatId, groupPerson.getPinnedMessageId()));
      } catch (TelegramApiException e) {
        log.debug(
            "Не удалось удалить старое сообщение {} в чате {}: {}",
            groupPerson.getPinnedMessageId(),
            chatId,
            e.getMessage());
      }
    }

    try {
      SendMessage sendMessage = new SendMessage(chatId, text);
      sendMessage.enableMarkdown(true);
      Message sent = execute(sendMessage);

      groupPerson.setPinnedMessageId(sent.getMessageId());
      personRepository.save(groupPerson);

      try {
        PinChatMessage pin =
            PinChatMessage.builder()
                .chatId(chatId)
                .messageId(sent.getMessageId())
                .disableNotification(true)
                .build();
        execute(pin);
      } catch (TelegramApiException e) {
        log.debug("Нет прав на закреп сообщения в чате {}: {}", chatId, e.getMessage());
      }
    } catch (TelegramApiException e) {
      if (isDeadUserError(e)) {
        cleanupDeadPerson(chatId, e.getMessage());
      } else {
        log.error("Не удалось отправить авто-расписание в чат {}: {}", chatId, e.getMessage());
      }
    }
  }

public void broadcastAsync(List<SendMessage> messages, String reportChatId) {
    if (!isBroadcasting.compareAndSet(false, true)) {
        if (reportChatId != null) {
            try {
                SendMessage m = new SendMessage(reportChatId, "⚠️ *Рассылка уже идет.* Дождитесь окончания текущей.");
                m.enableMarkdown(true);
                execute(m);
            } catch (Exception ignored) {}
        }
        return;
    }

    if (messages == null || messages.isEmpty()) {
        isBroadcasting.set(false);
        return;
    }

    java.util.concurrent.CompletableFuture.runAsync(() -> {
        try {
            int success = 0;
            int blocked = 0;
            int errors = 0;

            for (SendMessage message : messages) {
                try {
                    execute(message);
                    success++;
                } catch (org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException e) {
                    if (e.getErrorCode() == 429) {
                        int retry = 3; // по умолчанию ждем 3 сек
                        if (e.getParameters() != null && e.getParameters().getRetryAfter() != null) {
                            retry = e.getParameters().getRetryAfter();
                        } else {
                            java.util.regex.Matcher m = java.util.regex.Pattern.compile("retry after (\\d+)").matcher(e.getMessage());
                            if (m.find()) retry = Integer.parseInt(m.group(1));
                        }
                        log.warn("Флуд-контроль (429). Пауза {} сек...", retry);
                        try {
                            Thread.sleep((retry + 1) * 1000L);
                            execute(message);
                            success++;
                        } catch (Exception ex) {
                            errors++;
                        }
                    } else if (isDeadUserError(e)) {
                        cleanupDeadPerson(message.getChatId(), e.getMessage());
                        blocked++;
                    } else {
                        log.warn("Ошибка рассылки в chatId {}: {}", message.getChatId(), e.getMessage());
                        errors++;
                    }
                } catch (org.telegram.telegrambots.meta.exceptions.TelegramApiException e) {
                    if (isDeadUserError(e)) {
                        cleanupDeadPerson(message.getChatId(), e.getMessage());
                        blocked++;
                    } else {
                        log.warn("Ошибка рассылки в chatId {}: {}", message.getChatId(), e.getMessage());
                        errors++;
                    }
                }

                try {
                    Thread.sleep(RATE_LIMIT_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            if (reportChatId != null) {
                try {
                    SendMessage report = new SendMessage(
                        reportChatId,
                        String.format(
                            "📊 *Рассылка завершена!*\nДоставлено: *%d*\nУдалено неактивных из БД: *%d*\nПрочих ошибок: *%d*",
                            success, blocked, errors
                        )
                    );
                    report.enableMarkdown(true);
                    execute(report);
                } catch (org.telegram.telegrambots.meta.exceptions.TelegramApiException e) {
                    log.error("Ошибка отправки отчета: {}", e.getMessage());
                }
            }
        } finally {
            isBroadcasting.set(false);
        }
    });
}
  public void sendMessage(List<SendMessage> messages) {
    if (messages == null || messages.isEmpty()) {
      return;
    }

    if (messages.size() > 3) {
      broadcastAsync(messages, null);
      return;
    }

    for (SendMessage message : messages) {
      try {
        execute(message);
      } catch (TelegramApiException e) {
        if (isDeadUserError(e)) {
          cleanupDeadPerson(message.getChatId(), e.getMessage());
        } else {
          log.error(
              "Не удалось отправить сообщение в chatId {}: {}",
              message.getChatId(),
              e.getMessage());
        }
      }
    }
  }
}
