package ru.azenizzka.telegram.commands;

import java.util.List;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import ru.azenizzka.entities.Person;
import ru.azenizzka.services.StatisticsService;
import ru.azenizzka.utils.MessagesConfig;

@Component
public class ReportCommand implements Command {

  private final StatisticsService statisticsService;

  public ReportCommand(StatisticsService statisticsService) {
    this.statisticsService = statisticsService;
  }

  @Override
  public String getCommand() {
    return MessagesConfig.REPORT_COMMAND;
  }

  @Override
  public boolean isRequiredAdminRights() {
    return false;
  }

  @Override
  public List<SendMessage> handle(Update update, Person person) {
    statisticsService.sendDailyReport();
    return List.of(new SendMessage(person.getChatId(), "Ежедневный отчёт отправлен в аудит-чат."));
  }
}
