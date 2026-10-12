package ru.azenizzka.telegram.handlers;

import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import ru.azenizzka.configuration.TelegramBotConfiguration;
import ru.azenizzka.entities.Person;
import ru.azenizzka.telegram.commands.StartCommand;
import ru.azenizzka.telegram.keyboards.MainKeyboard;
import ru.azenizzka.telegram.messages.CustomMessage;
import ru.azenizzka.telegram.messages.ErrorMessage;
import ru.azenizzka.utils.MessagesConfig;

@Component
@Slf4j
public class MasterHandler implements Handler {

  private final CommandsHandler commandsHandler;
  private final BellTypeHandler bellTypeHandler;
  private final ChangeGroupHandler changeGroupHandler;
  private final RecessHandler recessHandler;
  private final AuditLogHandler auditLogHandler;
  private final CallbackQueryHandler callbackQueryHandler;
  private final StartCommand startCommand;

  private final TelegramBotConfiguration configuration;

  public MasterHandler(
      TelegramBotConfiguration configuration,
      CommandsHandler commandsHandler,
      BellTypeHandler bellTypeHandler,
      ChangeGroupHandler changeGroupHandler,
      RecessHandler recessHandler,
      AuditLogHandler auditLogHandler,
      CallbackQueryHandler callbackQueryHandler,
      StartCommand startCommand) {
    this.configuration = configuration;
    this.commandsHandler = commandsHandler;
    this.bellTypeHandler = bellTypeHandler;
    this.changeGroupHandler = changeGroupHandler;
    this.recessHandler = recessHandler;
    this.auditLogHandler = auditLogHandler;
    this.callbackQueryHandler = callbackQueryHandler;
    this.startCommand = startCommand;
  }

  @Override
  public List<SendMessage> handle(Update update, Person person) {
    if (update.hasCallbackQuery()) {
      callbackQueryHandler.handle(update.getCallbackQuery(), person);
      return List.of();
    }

    String text = update.hasMessage() && update.getMessage().hasText()
        ? update.getMessage().getText().trim()
        : "";

    // Если пишут в аудит-чате:
    if (person.getChatId().equals(configuration.getAuditLogChatId())) {
      person.setAdmin(true);
      // 1. Если это реплай на лог ошибки (/ban или ответ юзеру) -> в AuditLogHandler
      if (update.hasMessage() && update.getMessage().isReply()) {
        return auditLogHandler.handle(update, person);
      }
      // 2. Если это команда (например, /broadcast или /info) -> выполняем через CommandsHandler
      if (text.startsWith("/")) {
        return commandsHandler.handle(update, person);
      }
      // 3. Обычные сообщения в аудит-чате игнорируем
      return List.of();
    }

    List<SendMessage> messages = new ArrayList<>();

    if (person.isBanned()) {
      messages.add(new ErrorMessage(person.getChatId(), "Ошибка доступа к боту.."));
      return messages;
    }

    if (text.equalsIgnoreCase(MessagesConfig.START_COMMAND) || text.toLowerCase().startsWith("/start")) {
      person.setInputType(InputType.COMMAND);
      return startCommand.handle(update, person);
    }

    if (text.equals(MessagesConfig.RETURN_COMMAND)) {
      person.setInputType(InputType.COMMAND);
      CustomMessage backMsg = new CustomMessage(person.getChatId(), "Главное меню:");
      MainKeyboard.addKeyboard(backMsg, person.isCompactMenu());
      return List.of(backMsg);
    }

    // Если нажали любую кнопку главного меню или ввели цифры, сбрасываем застрявший стейт в COMMAND
    if ((!text.startsWith("/") && text.matches(".*\\d{2,3}.*"))
        || text.equals("ℹ️ О боте")
        || text.equals(MessagesConfig.SETTINGS_COMMAND)
        || text.equals(MessagesConfig.RECESS_SCHEDULE_COMMAND)
        || text.equals(MessagesConfig.LESSON_SCHEDULE_COMMAND)) {
      person.setInputType(InputType.COMMAND);
    }

    try {
      if (person.getInputType() == null) {
        person.setInputType(InputType.COMMAND);
      }

      switch (person.getInputType()) {
        case COMMAND, SETTINGS_MAIN -> messages.addAll(commandsHandler.handle(update, person));
        case BELL_TYPE -> {
          List<SendMessage> bellRes = bellTypeHandler.handle(update, person);
          for (SendMessage m : bellRes) {
            if (person.getInputType() == InputType.COMMAND) {
              MainKeyboard.addKeyboard(m, person.isCompactMenu());
            }
          }
          messages.addAll(bellRes);
        }
        case GROUP -> messages.addAll(changeGroupHandler.handle(update, person));
        case DAY -> messages.addAll(recessHandler.handle(update, person));
      }

      boolean hasError = messages.stream().anyMatch(m -> m instanceof ErrorMessage);

      if (hasError) {
        messages.addAll(auditLogHandler.handle(update, person));
      }

    } catch (Exception e) {
      log.error("Unhandled exception for user {}: ", person.getChatId(), e);
      messages.add(new ErrorMessage(person.getChatId(), "Произошла системная ошибка."));
      messages.addAll(auditLogHandler.handle(update, person));
    }

    return messages;
  }
}
