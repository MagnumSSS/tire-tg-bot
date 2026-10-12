package ru.azenizzka.telegram.handlers;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import ru.azenizzka.entities.Person;
import ru.azenizzka.services.PersonService;
import ru.azenizzka.telegram.commands.Command;
import ru.azenizzka.telegram.keyboards.MainKeyboard;
import ru.azenizzka.telegram.messages.CustomMessage;
import ru.azenizzka.telegram.messages.ErrorMessage;
import ru.azenizzka.utils.DayUtil;
import ru.azenizzka.utils.MessagesConfig;

@Component
@RequiredArgsConstructor
public class CommandsHandler implements Handler {
  private final List<Command> commands;
  private final PersonService personService;
  private final RecessHandler recessHandler;

  @Override
  public List<SendMessage> handle(Update update, Person person) {
    String rawText = update.getMessage().getText().trim();
    String textMessage = rawText.toLowerCase();

    // 1. Мягкий фильтр: любые цифры = смена номера группы
    if (rawText.matches("^\\d{1,5}$")) {
      try {
        int groupNum = Integer.parseInt(rawText);
        person.setGroupNum(groupNum);
        personService.save(person);

        CustomMessage response = new CustomMessage(person.getChatId());
        MainKeyboard.addKeyboard(response, person.isCompactMenu());
        response.setText("✅ Группа успешно изменена на *" + groupNum + "*!");
        return List.of(response);
      } catch (NumberFormatException ignored) {
      }
    }

    // 2. Прямое нажатие на день недели с главного экрана
    try {
      DayUtil.convertStrToDay(textMessage);
      if (person.getGroupNum() == 0) {
        return List.of(new ErrorMessage(person.getChatId(), "Сначала укажите группу! Просто отправьте номер вашей группы цифрами в чат."));
      }
      return recessHandler.handle(update, person);
    } catch (Exception ignored) {
    }

    // 3. Поиск стандартных команд
    for (Command command : commands) {
      if (command.isRequiredAdminRights() && !person.isAdmin()) {
        continue;
      }

      if (textMessage.startsWith(command.getCommand().toLowerCase())) {
        List<SendMessage> res = command.handle(update, person);
        for (SendMessage msg : res) {
          if (msg.getReplyMarkup() instanceof org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup
              && !command.getCommand().equals(MessagesConfig.LESSON_SCHEDULE_COMMAND)
              && !command.getCommand().equals(MessagesConfig.RECESS_SCHEDULE_COMMAND)) {
            MainKeyboard.addKeyboard(msg, person.isCompactMenu());
          }
        }
        return res;
      }
    }

    SendMessage errorMessage =
        new ErrorMessage(
            update.getMessage().getChatId().toString(), MessagesConfig.UNKNOWN_COMMAND_EXCEPTION);

    return List.of(errorMessage);
  }
}
