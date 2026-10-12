package ru.azenizzka.telegram.handlers;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import ru.azenizzka.entities.Person;
import ru.azenizzka.services.LessonScheduleService;
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
  private static final Pattern SOFT_GROUP_PATTERN = Pattern.compile("(?<!\\d)\\d{2,3}(?!\\d)");

  private final List<Command> commands;
  private final PersonService personService;
  private final LessonScheduleService lessonScheduleService;
  private final RecessHandler recessHandler;

  @Override
  public List<SendMessage> handle(Update update, Person person) {
    String rawText = update.getMessage().getText().trim();
    String textMessage = rawText.toLowerCase();

    // 1. Прямое нажатие на день недели с главного экрана
    try {
      DayUtil.convertStrToDay(textMessage);
      if (person.getGroupNum() == 0) {
        return List.of(new ErrorMessage(person.getChatId(), "Сначала укажите группу! Просто отправьте номер вашей группы в чат (например: 214 или МР-131)."));
      }
      return recessHandler.handle(update, person);
    } catch (Exception ignored) {
    }

    // 2. Поиск стандартных команд и кнопок меню
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

    // 3. Софт-фильтр номера группы (понимает "234", "234 группа", "МР-131", "группа 101" и т.д.)
    if (!rawText.startsWith("/")) {
      Matcher matcher = SOFT_GROUP_PATTERN.matcher(rawText);
      if (matcher.find()) {
        try {
          int groupNum = Integer.parseInt(matcher.group());
          if (!lessonScheduleService.isGroupExists(groupNum)) {
            return List.of(new ErrorMessage(person.getChatId(), MessagesConfig.GROUP_NOT_FOUND_EXCEPTION));
          }
          person.setGroupNum(groupNum);
          personService.save(person);

          CustomMessage response = new CustomMessage(person.getChatId());
          MainKeyboard.addKeyboard(response, person.isCompactMenu());
          response.setText("✅ Группа успешно изменена на *" + groupNum + "*!");
          return List.of(response);
        } catch (NumberFormatException ignored) {
        }
      }
    }

    SendMessage errorMessage =
        new ErrorMessage(
            update.getMessage().getChatId().toString(), MessagesConfig.UNKNOWN_COMMAND_EXCEPTION);

    return List.of(errorMessage);
  }
}
