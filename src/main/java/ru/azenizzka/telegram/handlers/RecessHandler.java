package ru.azenizzka.telegram.handlers;

import java.util.List;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import ru.azenizzka.entities.Person;
import ru.azenizzka.services.BellScheduleService;
import ru.azenizzka.services.LessonScheduleService;
import ru.azenizzka.telegram.TelegramBot;
import ru.azenizzka.telegram.keyboards.MainKeyboard;
import ru.azenizzka.telegram.keyboards.ReportKeyboard;
import ru.azenizzka.telegram.messages.CustomMessage;
import ru.azenizzka.telegram.messages.ErrorMessage;
import ru.azenizzka.utils.Day;
import ru.azenizzka.utils.DayUtil;
import ru.azenizzka.utils.MessagesConfig;

@Component
public class RecessHandler implements Handler {

  private final LessonScheduleService lessonScheduleService;
  private final BellScheduleService bellScheduleService;

  public RecessHandler(
      LessonScheduleService lessonScheduleService,
      BellScheduleService bellScheduleService) {
    this.lessonScheduleService = lessonScheduleService;
    this.bellScheduleService = bellScheduleService;
  }

  @Override
  public List<SendMessage> handle(Update update, Person person) {
    person.setInputType(InputType.COMMAND);

    String textMessage = update.getMessage().getText().toLowerCase();

    try {
      Day day = DayUtil.convertStrToDay(textMessage);

      CustomMessage pleaseWaitMessage =
          new CustomMessage(
              person.getChatId(),
              "⏳ Пожалуйста, подождите. Получаю расписание..");
      MainKeyboard.addKeyboard(pleaseWaitMessage, person.isCompactMenu());
      TelegramBot.getInstance().sendMessage(List.of(pleaseWaitMessage));

      List<List<String>> lessons = lessonScheduleService.getLessons(person.getGroupNum(), day);

      if (lessons.isEmpty()) {
        CustomMessage noLessons = new CustomMessage(person.getChatId(), MessagesConfig.NO_LESSONS_MESSAGE);
        MainKeyboard.addKeyboard(noLessons, person.isCompactMenu());
        return List.of(noLessons);
      }

      StringBuilder result =
          new StringBuilder(
              "Расписание *" + person.getGroupNum() + "* группы\n*" + textMessage + "*\n\n");

      for (List<String> list : lessons) {
        int lessonNum = 0;
        try {
          lessonNum = Integer.parseInt(list.get(0).replaceAll("[^0-9]", ""));
        } catch (Exception ignored) {}

        String bellTime = bellScheduleService.getBellTime(day, lessonNum);

        result.append("*").append(list.get(0)).append(" пара:* ").append(list.get(1)).append("\n");
        result.append("*Кабинет:* ").append(list.get(2)).append("\n");
        if (!bellTime.isBlank()) {
          result.append("⏱ `").append(bellTime).append("`\n");
        }
        result.append("\n");
      }

      SendMessage scheduleMessage = new SendMessage(person.getChatId(), result.toString().trim());
      scheduleMessage.enableMarkdown(true);
      scheduleMessage.setReplyMarkup(ReportKeyboard.getKeyboard(textMessage));

      return List.of(scheduleMessage);
    } catch (Exception e) {
      return List.of(new ErrorMessage(person.getChatId(), e.getMessage()));
    }
  }
}
