package ru.azenizzka.telegram;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import ru.azenizzka.configuration.TelegramBotConfiguration;
import ru.azenizzka.entities.Person;
import ru.azenizzka.repositories.PersonRepository;
import ru.azenizzka.telegram.handlers.InputType;
import ru.azenizzka.telegram.handlers.MasterHandler;

@Component
@Slf4j
public class TelegramBot extends TelegramLongPollingBot {

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

    if (update.hasCallbackQuery()) {
      chatId = String.valueOf(update.getCallbackQuery().getFrom().getId());
      username = update.getCallbackQuery().getFrom().getUserName();
    } else if (update.hasMessage() && update.getMessage().hasText()) {
      chatId = update.getMessage().getChatId().toString();
      username = update.getMessage().getChat().getUserName();
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
      } else {
        person.setUsername(username);
      }

      if (configuration.getAdminChatId() != null
          && !configuration.getAdminChatId().isBlank()
          && configuration.getAdminChatId().equals(chatId)) {
        person.setAdmin(true);
      }

      List<SendMessage> responses = masterHandler.handle(update, person);

      personRepository.save(person);

      sendMessage(responses);
    } catch (Exception e) {
      log.error("Ошибка при обработке события от chatId {}: {}", chatId, e.getMessage(), e);
    }
  }

  public void sendMessage(List<SendMessage> messages) {
    if (messages == null || messages.isEmpty()) {
      return;
    }

    for (SendMessage message : messages) {
      try {
        execute(message);
      } catch (TelegramApiException e) {
        log.error("Не удалось отправить сообщение в chatId {}: {}", message.getChatId(), e.getMessage());
      }
    }
  }
}
