package ru.azenizzka.telegram.handlers;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import ru.azenizzka.entities.Person;
import ru.azenizzka.telegram.commands.ChangeGroupCommand;
import ru.azenizzka.telegram.commands.Command;
import ru.azenizzka.telegram.commands.ReturnCommand;
import ru.azenizzka.telegram.messages.ErrorMessage;
import ru.azenizzka.utils.MessagesConfig;

@Component
public class SettingHandler implements Handler {
  private final List<Command> commands;

  public SettingHandler(ChangeGroupCommand changeGroupCommand, ReturnCommand returnCommand) {
    commands = new ArrayList<>();
    commands.add(returnCommand);
    commands.add(changeGroupCommand);
  }

  @Override
  public List<SendMessage> handle(Update update, Person person) {
    String textMessage = update.getMessage().getText().toLowerCase();

    for (Command command : commands) {
      if (command.isRequiredAdminRights() && !person.isAdmin()) {
        continue;
      }

      if (command.getCommand().equalsIgnoreCase(textMessage)) {
        return command.handle(update, person);
      }
    }

    SendMessage errorMessage =
        new ErrorMessage(person.getChatId(), MessagesConfig.UNKNOWN_COMMAND_EXCEPTION);
    person.setInputType(InputType.COMMAND);

    return List.of(errorMessage);
  }
}
