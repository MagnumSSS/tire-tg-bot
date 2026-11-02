package ru.azenizzka.telegram.commands;

import java.util.List;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import ru.azenizzka.entities.Person;
import ru.azenizzka.telegram.handlers.InputType;
import ru.azenizzka.telegram.keyboards.KeyboardType;
import ru.azenizzka.telegram.messages.CustomMessage;
import ru.azenizzka.utils.MessagesConfig;

@Component
public class ScheduleCommand implements Command {
  @Override
  public String getCommand() {
    return MessagesConfig.LESSON_SCHEDULE_COMMAND;
  }

  @Override
  public boolean isRequiredAdminRights() {
    return false;
  }

  @Override
  public List<SendMessage> handle(Update update, Person person) {
    if (person.getGroupNum() == 0) {
      return List.of(
          new SendMessage(
              person.getChatId(),
              "⚠️ Вы еще не выбрали группу.\nПожалуйста, отправьте номер вашей группы в чат (только цифры, например: 232)."));
    }

    SendMessage message = new CustomMessage(person.getChatId(), KeyboardType.DAY);
    person.setInputType(InputType.DAY);
    message.setText(MessagesConfig.DAY_MESSAGE);
    return List.of(message);
  }
}
