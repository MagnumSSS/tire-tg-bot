package ru.azenizzka.telegram.commands;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import ru.azenizzka.entities.Person;
import ru.azenizzka.services.PersonService;
import ru.azenizzka.telegram.TelegramBot;
import ru.azenizzka.telegram.messages.ErrorMessage;
import ru.azenizzka.telegram.messages.NotifyMessage;
import ru.azenizzka.utils.MessagesConfig;

@Component
public class BroadcastCommand implements Command {
  private final PersonService personService;

  public BroadcastCommand(PersonService personService) {
    this.personService = personService;
  }

  @Override
  public String getCommand() {
    return MessagesConfig.BROADCAST_COMMAND;
  }

  @Override
  public boolean isRequiredAdminRights() {
    return true;
  }

  @Override
  public List<SendMessage> handle(Update update, Person person) {
    String rawText = update.getMessage().getText().trim();

    if (rawText.length() <= getCommand().length()) {
      return List.of(new ErrorMessage(person.getChatId(), MessagesConfig.NULL_BROADCAST_MESSAGE_EXCEPTION));
    }

    String broadcastText = rawText.substring(getCommand().length()).trim();
    List<SendMessage> list = new ArrayList<>();

    for (Person client : personService.findAll()) {
      if (client.getChatId() != null
          && !client.getChatId().equals(person.getChatId())
          && !client.isBanned()) {
        list.add(new NotifyMessage(client.getChatId(), broadcastText));
      }
    }

    TelegramBot.getInstance().broadcastAsync(list, person.getChatId());

    SendMessage startedMsg = new SendMessage(
        person.getChatId(),
        "🚀 Запущена фоновая рассылка на *" + list.size() + "* чатов (~18 сообщ/сек).\nПо окончании пришлю отчёт и очищу БД от заблокировавших бота.");
    startedMsg.enableMarkdown(true);
    return List.of(startedMsg);
  }
}
