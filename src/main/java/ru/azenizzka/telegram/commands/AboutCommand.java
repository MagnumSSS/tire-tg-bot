package ru.azenizzka.telegram.commands;

import java.util.List;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import ru.azenizzka.entities.Person;

@Component
public class AboutCommand implements Command {

  @Override
  public String getCommand() {
    return "ℹ️ О боте";
  }

  @Override
  public boolean isRequiredAdminRights() {
    return false;
  }

  @Override
  public List<SendMessage> handle(Update update, Person person) {
    SendMessage message = new SendMessage(person.getChatId(),
        "*Бот Расписания*\n\n"
        + "Бот берет расписание с сайта и скидывает вам в телеграм.\n\n"
        + "Бот предоставляется по принципу как есть. Разработчик не несёт ответственности за пропущенные пары, отмены занятий или внезапные изменения в аудиториях.\n"
        + "Единственный официальный источник информации это сайт `ntmm.ru`.\n"
        + "Данные парсятся раз в 10 минут. При сбоях на сайте колледжа бот отображает последний сохранённый кэш.\n\n"
        + "Разработчики: @xvycy, @lalalaalalaLaAAaA");

    message.enableMarkdown(true);
    return List.of(message);
  }
}
