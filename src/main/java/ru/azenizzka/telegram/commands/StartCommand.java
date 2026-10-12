package ru.azenizzka.telegram.commands;

import java.util.List;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import ru.azenizzka.entities.Person;
import ru.azenizzka.telegram.handlers.InputType;
import ru.azenizzka.telegram.keyboards.MainKeyboard;
import ru.azenizzka.telegram.messages.CustomMessage;
import ru.azenizzka.utils.MessagesConfig;

@Component
public class StartCommand implements Command {
  @Override
  public String getCommand() {
    return MessagesConfig.START_COMMAND;
  }

  @Override
  public boolean isRequiredAdminRights() {
    return false;
  }

  @Override
  public List<SendMessage> handle(Update update, Person person) {
    person.setInputType(InputType.COMMAND);

    String groupInfo = person.getGroupNum() == 0
        ? "❗️ *Сейчас группа не выбрана.*"
        : "🎓 Ваша текущая группа: *" + person.getGroupNum() + "*";

    String text = "👋 *Привет! Это бот расписания колледжа.*\n\n"
        + groupInfo + "\n\n"
        + "📖 *Как пользоваться ботом:*\n"
        + "• *Смена группы:* просто напишите номер вашей группы цифрами в чат в любой момент (например: `261`).\n"
        + "• *Расписание пар:* нажимайте на кнопки дней недели («Сегодня», «Завтра» или конкретный день) в меню внизу.\n"
        + "• *Звонки:* кнопка «Звонки» позволяет посмотреть время начала и конца пар.\n"
        + "• *Настройки:* там можно включить/выключить авто-уведомления об изменениях в расписании и выбрать вид нижнего меню (полное со всеми днями или компактное).\n"
        + "• *Починка бота:* команда `/start` в любой непонятной ситуации сбрасывает бота в главное меню.";

    CustomMessage message = new CustomMessage(person.getChatId(), text);
    MainKeyboard.addKeyboard(message, person.isCompactMenu());
    return List.of(message);
  }
}
