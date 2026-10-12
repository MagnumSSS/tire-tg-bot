package ru.azenizzka.telegram.commands;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import ru.azenizzka.entities.Person;
import ru.azenizzka.services.PersonService;
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
    List<SendMessage> list = new ArrayList<>();
    String rawText = update.getMessage().getText().trim();

    if (rawText.length() <= getCommand().length()) {
      list.add(new ErrorMessage(person.getChatId(), MessagesConfig.NULL_BROADCAST_MESSAGE_EXCEPTION));
      return list;
    }

    String broadcastText = rawText.substring(getCommand().length()).trim();

    for (Person client : personService.findAll()) {
      if (client.getChatId() != null && !client.getChatId().startsWith("-") && !client.isBanned()) {
        list.add(new NotifyMessage(client.getChatId(), broadcastText));
      }
    }

    return list;
  }
}
