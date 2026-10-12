package ru.azenizzka.telegram.keyboards;

import java.util.ArrayList;
import java.util.List;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import ru.azenizzka.utils.MessagesConfig;

public class MainKeyboard {

  public static void addKeyboard(SendMessage message) {
    addKeyboard(message, false);
  }

  public static void addKeyboard(SendMessage message, boolean compact) {
    message.setReplyMarkup(getKeyboardMarkup(compact));
  }

  public static ReplyKeyboardMarkup getKeyboardMarkup(boolean compact) {
    ReplyKeyboardMarkup keyboardMarkup = new ReplyKeyboardMarkup();
    List<KeyboardRow> keyboard = new ArrayList<>();

    if (!compact) {
      KeyboardRow row1 = new KeyboardRow();
      row1.add("Понедельник");
      row1.add("Вторник");
      row1.add("Среда");
      keyboard.add(row1);

      KeyboardRow row2 = new KeyboardRow();
      row2.add("Четверг");
      row2.add("Пятница");
      row2.add("Суббота");
      keyboard.add(row2);
    }

    KeyboardRow rowToday = new KeyboardRow();
    rowToday.add("Сегодня");
    rowToday.add("Завтра");
    if (compact) {
      rowToday.add(MessagesConfig.LESSON_SCHEDULE_COMMAND);
    }
    keyboard.add(rowToday);

    KeyboardRow rowBottom = new KeyboardRow();
    rowBottom.add(MessagesConfig.RECESS_SCHEDULE_COMMAND);
    rowBottom.add(MessagesConfig.SETTINGS_COMMAND);
    rowBottom.add("ℹ️ О боте");
    keyboard.add(rowBottom);

    keyboardMarkup.setResizeKeyboard(true);
    keyboardMarkup.setKeyboard(keyboard);
    return keyboardMarkup;
  }
}
