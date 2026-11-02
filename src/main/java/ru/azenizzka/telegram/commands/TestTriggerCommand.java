package ru.azenizzka.telegram.commands;

import java.util.List;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import ru.azenizzka.configuration.TelegramBotConfiguration;
import ru.azenizzka.entities.Person;
import ru.azenizzka.services.LessonScheduleService;
import ru.azenizzka.telegram.messages.ErrorMessage;

@Component
public class TestTriggerCommand implements Command {

  private final LessonScheduleService lessonScheduleService;
  private final TelegramBotConfiguration configuration;
  private final ru.azenizzka.services.StatisticsService statisticsService;

  public TestTriggerCommand(
      LessonScheduleService lessonScheduleService,
      TelegramBotConfiguration configuration,
      ru.azenizzka.services.StatisticsService statisticsService) {
    this.lessonScheduleService = lessonScheduleService;
    this.configuration = configuration;
    this.statisticsService = statisticsService;
  }

  @Override
  public String getCommand() {
    return "/test";
  }

  @Override
  public boolean isRequiredAdminRights() {
    return false;
  }

  @Override
  public List<SendMessage> handle(Update update, Person person) {
    if (!configuration.isTestMode()) {
      return List.of(new ErrorMessage(person.getChatId(), "Тестовый режим отключен."));
    }

    String text = update.getMessage().getText().trim().toLowerCase();
    String[] parts = text.split("\\s+");

    if (parts.length < 2) {
      SendMessage msg =
          new SendMessage(
              person.getChatId(),
              "Команды:\n`/test morning`\n`/test evening`\n`/test change <group>`");
      msg.enableMarkdown(true);
      return List.of(msg);
    }

    String action = parts[1];

    if (action.equals("morning")) {
      lessonScheduleService.sendMorningGroupSchedules();
      return List.of(new SendMessage(person.getChatId(), "Утренняя рассылка запущена."));
    }

    if (action.equals("evening")) {
      lessonScheduleService.sendEveningGroupSchedules();
      return List.of(new SendMessage(person.getChatId(), "Вечерняя рассылка запущена."));
    }

    if (action.equals("report")) {
      statisticsService.sendDailyReport();
      return List.of(
          new SendMessage(person.getChatId(), "Ежедневный отчёт отправлен в аудит-чат."));
    }

    if (action.equals("change")) {
      int group = 111;
      if (parts.length >= 3) {
        try {
          group = Integer.parseInt(parts[2]);
        } catch (Exception ignored) {
        }
      }
      lessonScheduleService.applyTestScheduleChange(group);
      return List.of(
          new SendMessage(person.getChatId(), "Изменение для группы " + group + " сгенерировано."));
    }

    return List.of(new ErrorMessage(person.getChatId(), "Неизвестное действие."));
  }
}
