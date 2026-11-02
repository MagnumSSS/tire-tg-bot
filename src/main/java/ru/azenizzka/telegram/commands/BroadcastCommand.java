package ru.azenizzka.telegram.commands;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import ru.azenizzka.entities.Person;
import ru.azenizzka.services.PersonService;
import ru.azenizzka.telegram.TelegramBot;
import ru.azenizzka.telegram.messages.ErrorMessage;
import ru.azenizzka.utils.MessagesConfig;

@Component
@RequiredArgsConstructor
public class BroadcastCommand implements Command {
  private final PersonService personService;

  @Override
  public String getCommand() {
    return "/broadcast";
  }

  @Override
  public boolean isRequiredAdminRights() {
    return true;
  }

  @Override
  public List<SendMessage> handle(Update update, Person person) {
    String rawText = update.getMessage().getText().trim();
    
    if (rawText.length() <= getCommand().length()) {
      return List.of(
          new ErrorMessage(person.getChatId(), MessagesConfig.NULL_BROADCAST_MESSAGE_EXCEPTION));
    }

    String broadcastText = rawText.substring(getCommand().length()).trim();
    List<SendMessage> list = new ArrayList<>();

    for (Person client : personService.findAll()) {
      if (client.getChatId() != null
          && !client.getChatId().equals(person.getChatId())
          && !client.isBanned()) {
          
        // Используем базовый SendMessage с отключенным Markdown
        SendMessage msg = new SendMessage(client.getChatId(), broadcastText);
        msg.enableMarkdown(false);
        list.add(msg);
      }
    }

    TelegramBot.getInstance().broadcastAsync(list, person.getChatId());

    SendMessage startedMsg =
        new SendMessage(
            person.getChatId(),
            "🚀 Запущена фоновая рассылка на *"
                + list.size()
                + "* чатов (~18 сообщ/сек).\nПо окончании пришлю отчёт и очищу БД от заблокировавших бота.");
    startedMsg.enableMarkdown(true);
    
    return List.of(startedMsg);
  }
}
