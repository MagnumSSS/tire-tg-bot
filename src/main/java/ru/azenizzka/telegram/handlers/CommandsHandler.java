package ru.azenizzka.telegram.handlers;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove;
import ru.azenizzka.entities.Person;
import ru.azenizzka.services.BellScheduleService;
import ru.azenizzka.services.LessonScheduleService;
import ru.azenizzka.services.PersonService;
import ru.azenizzka.telegram.TelegramBot;
import ru.azenizzka.telegram.commands.AboutCommand;
import ru.azenizzka.telegram.commands.Command;
import ru.azenizzka.telegram.commands.SettingsCommand;
import ru.azenizzka.telegram.keyboards.MainKeyboard;
import ru.azenizzka.telegram.messages.CustomMessage;
import ru.azenizzka.telegram.messages.ErrorMessage;
import ru.azenizzka.utils.Day;
import ru.azenizzka.utils.DayUtil;
import ru.azenizzka.utils.MessagesConfig;

@Component
@RequiredArgsConstructor
public class CommandsHandler implements Handler {
  private static final Pattern SOFT_GROUP_PATTERN = Pattern.compile("(?<!\\d)\\d{2,3}(?!\\d)");

  private final List<Command> commands;
  private final PersonService personService;
  private final LessonScheduleService lessonScheduleService;
  private final BellScheduleService bellScheduleService;
  private final RecessHandler recessHandler;
  private final SettingsCommand settingsCommand;
  private final AboutCommand aboutCommand;

  public List<SendMessage> handleGroupCommand(Update update, Person person) {
    String rawText = update.getMessage().getText().trim();
    String cleaned = rawText.replaceAll("@\\S+", "").trim();
    String lower = cleaned.toLowerCase();
    Long fromUserId = update.getMessage().getFrom().getId();

    if (lower.equals("/start") || lower.equals("/help")) {
      SendMessage msg = new SendMessage(
          person.getChatId(),
          "👋 *Бот расписания для группового чата*\n\n"
              + "📌 *Команды чата:*\n"
              + "• `/today` — расписание на сегодня\n"
              + "• `/tomorrow` — расписание на завтра\n"
              + "• `/bells` — расписание звонков\n"
              + "• `/settings` — настройки авто-рассылки и закрепа (для админов)\n"
              + "• `/group 234` — установить номер группы для беседы (для админов)\n\n"
              + "_Автоматическая отправка и закреп расписания: в 08:00 (на сегодня) и в 18:00 (на завтра), если включено в /settings._");
      msg.enableMarkdown(true);
      msg.setReplyMarkup(new ReplyKeyboardRemove(true));
      return List.of(msg);
    }

    if (lower.startsWith("/group")) {
      if (!TelegramBot.getInstance().isGroupAdmin(person.getChatId(), fromUserId)) {
        return List.of(new ErrorMessage(person.getChatId(), "⛔ Менять группу беседы могут только администраторы чата!"));
      }
      Matcher m = SOFT_GROUP_PATTERN.matcher(cleaned);
      if (!m.find()) {
        return List.of(new ErrorMessage(person.getChatId(), "Укажите номер группы, например: `/group 234`"));
      }
      int groupNum = Integer.parseInt(m.group());
      if (!lessonScheduleService.isGroupExists(groupNum)) {
        return List.of(new ErrorMessage(person.getChatId(), MessagesConfig.GROUP_NOT_FOUND_EXCEPTION));
      }
      person.setGroupNum(groupNum);
      personService.save(person);

      SendMessage response = new SendMessage(
          person.getChatId(),
          "✅ Группа чата установлена на *" + groupNum + "*!\n"
              + "🔔 Чтобы включить ежедневную отправку и закреп расписания в 08:00 и 18:00, откройте `/settings`.\n"
              + "_(Не забудьте выдать боту права администратора на закрепление и удаление сообщений)_");
      response.enableMarkdown(true);
      response.setReplyMarkup(new ReplyKeyboardRemove(true));
      return List.of(response);
    }

    if (lower.equals("/settings")) {
      return settingsCommand.handle(update, person);
    }

    if (lower.equals("/about")) {
      return aboutCommand.handle(update, person);
    }

    if (lower.equals("/bells")) {
      StringBuilder bells = new StringBuilder("🔔 *Основное расписание звонков (ВТ–ПТ):*\n");
      for (int i = 1; i <= 6; i++) {
        bells.append("*").append(i).append(" пара:* `").append(bellScheduleService.getBellTime(Day.TUESDAY, i)).append("`\n");
      }
      bells.append("\n🔔 *Понедельник:*\n");
      for (int i = 1; i <= 6; i++) {
        bells.append("*").append(i).append(" пара:* `").append(bellScheduleService.getBellTime(Day.MONDAY, i)).append("`\n");
      }
      bells.append("\n🔔 *Суббота:*\n");
      for (int i = 1; i <= 6; i++) {
        bells.append("*").append(i).append(" пара:* `").append(bellScheduleService.getBellTime(Day.SATURDAY, i)).append("`\n");
      }
      SendMessage msg = new SendMessage(person.getChatId(), bells.toString().trim());
      msg.enableMarkdown(true);
      return List.of(msg);
    }

    if (lower.equals("/today") || lower.equals("/tomorrow")) {
      if (person.getGroupNum() == 0) {
        return List.of(new ErrorMessage(person.getChatId(), "Сначала укажите группу для чата командой `/group 234` (только для админов беседы)."));
      }
      return recessHandler.handle(update, person);
    }

    return List.of();
  }

  @Override
  public List<SendMessage> handle(Update update, Person person) {
    String rawText = update.getMessage().getText().trim();
    String textMessage = rawText.toLowerCase();

    try {
      DayUtil.convertStrToDay(textMessage);
      if (person.getGroupNum() == 0) {
        return List.of(new ErrorMessage(person.getChatId(), "Вы еще не выбрали группу. Отправьте номер вашей группы в чат (например, 234).
Если возникла проблема — напишите @xvycy"));
      }
      return recessHandler.handle(update, person);
    } catch (Exception ignored) {
    }

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
