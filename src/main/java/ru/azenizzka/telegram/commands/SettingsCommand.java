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

@Component
public class SettingsCommand implements Command {
  public static final String TOGGLE_NOTIFY_CALLBACK = "TOGGLE_NOTIFY";
  public static final String TOGGLE_MENU_CALLBACK = "TOGGLE_MENU";
  public static final String TOGGLE_MINIMALIST_CALLBACK = "TOGGLE_MINIMALIST";

  @Override
  public String getCommand() {
    return "Настройки";
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
    boolean isGroupChat = person.getChatId() != null && person.getChatId().startsWith("-");
    String groupStr =
        person.getGroupNum() == 0 ? "Не выбрана" : String.valueOf(person.getGroupNum());
    String notifyStr = person.isNotificationsEnabled() ? "✅ Включены" : "❌ Выключены";
    String menuStr =
        person.isCompactMenu() ? "Компактное (Сегодня/Завтра)" : "Полное (Все дни недели)";
    String minStr = person.isMinimalistMode() ? "✅ Включен" : "❌ Выключен";

    if (isGroupChat) {
      return String.format(
          "⚙️ *Настройки беседы*\n\n"
              + "👥 *Группа:* %s\n"
              + "🔔 *Уведомления:* %s\n\n"
              + "Выберите, что хотите изменить:",
          groupStr, notifyStr);
    }

    return String.format(
        "⚙️ *Настройки*\n\n"
            + "👥 *Группа:* %s\n"
            + "🔔 *Уведомления:* %s\n"
            + "📱 *Вид меню:* %s\n"
            + "✨ *Минимализм:* %s\n\n"
            + "Выберите, что хотите изменить:",
        groupStr, notifyStr, menuStr, minStr);
  }

  public static InlineKeyboardMarkup buildInlineKeyboard(Person person) {
    boolean isGroupChat = person.getChatId() != null && person.getChatId().startsWith("-");
    List<List<InlineKeyboardButton>> rows = new ArrayList<>();

    InlineKeyboardButton notifyBtn = new InlineKeyboardButton();
    notifyBtn.setText(
        person.isNotificationsEnabled() ? "🔕 Выключить уведомления" : "🔔 Включить уведомления");
    notifyBtn.setCallbackData(TOGGLE_NOTIFY_CALLBACK);
    rows.add(List.of(notifyBtn));

    if (!isGroupChat) {
      InlineKeyboardButton menuBtn = new InlineKeyboardButton();
      menuBtn.setText(
          person.isCompactMenu() ? "🗂 Вернуть полное меню" : "📱 Включить компактное меню");
      menuBtn.setCallbackData(TOGGLE_MENU_CALLBACK);
      rows.add(List.of(menuBtn));

      InlineKeyboardButton minBtn = new InlineKeyboardButton();
      minBtn.setText(
          person.isMinimalistMode() ? "✨ Выключить минимализм" : "✨ Включить минимализм");
      minBtn.setCallbackData(TOGGLE_MINIMALIST_CALLBACK);
      rows.add(List.of(minBtn));
    }

    InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
    markup.setKeyboard(rows);
    return markup;
  }
}
