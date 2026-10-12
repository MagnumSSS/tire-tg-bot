package ru.azenizzka.telegram.commands;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import ru.azenizzka.entities.Person;
import ru.azenizzka.telegram.messages.CustomMessage;
import ru.azenizzka.utils.MessagesConfig;

@Component
public class SettingsCommand implements Command {

  public static final String TOGGLE_NOTIFY_CALLBACK = "settings_toggle_notify";
  public static final String TOGGLE_MENU_CALLBACK = "settings_toggle_menu";

  @Override
  public String getCommand() {
    return MessagesConfig.SETTINGS_COMMAND;
  }

  @Override
  public boolean isRequiredAdminRights() {
    return false;
  }

  @Override
  public List<SendMessage> handle(Update update, Person person) {
    CustomMessage message = new CustomMessage(person.getChatId(), buildText(person));
    message.setReplyMarkup(buildInlineKeyboard(person));
    return List.of(message);
  }

  public static String buildText(Person person) {
    String groupStr = person.getGroupNum() == 0 ? "Не выбрана" : String.valueOf(person.getGroupNum());
    String notifyStr = person.isNotificationsEnabled() ? "✅ Включены" : "❌ Выключены";
    String menuStr = person.isCompactMenu() ? "Компактное (Сегодня/Завтра)" : "Полное (Все дни недели)";

    return "⚙️ *Настройки профиля*\n\n"
        + "🎓 *Группа:* `" + groupStr + "`\n"
        + "🔔 *Оповещения об изменениях:* " + notifyStr + "\n"
        + "📱 *Главное меню:* " + menuStr + "\n\n"
        + "💡 _Чтобы сменить группу, просто отправьте её номер цифрами в чат (например: 232)._";
  }

  public static InlineKeyboardMarkup buildInlineKeyboard(Person person) {
    InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
    List<List<InlineKeyboardButton>> rows = new ArrayList<>();

    InlineKeyboardButton notifyBtn = new InlineKeyboardButton();
    notifyBtn.setText(person.isNotificationsEnabled() ? "🔕 Выключить уведомления" : "🔔 Включить уведомления");
    notifyBtn.setCallbackData(TOGGLE_NOTIFY_CALLBACK);
    rows.add(List.of(notifyBtn));

    InlineKeyboardButton menuBtn = new InlineKeyboardButton();
    menuBtn.setText(person.isCompactMenu() ? "📅 Включить полное меню (все дни)" : "📱 Включить компактное меню");
    menuBtn.setCallbackData(TOGGLE_MENU_CALLBACK);
    rows.add(List.of(menuBtn));

    markup.setKeyboard(rows);
    return markup;
  }
}
