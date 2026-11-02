package ru.azenizzka.telegram.keyboards;

import java.util.List;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

public class ReportKeyboard {

  public static final String REPORT_INIT_PREFIX = "REPORT_INIT:";
  public static final String REPORT_CONFIRM_PREFIX = "REPORT_CONFIRM:";
  public static final String REPORT_CANCEL = "REPORT_CANCEL";

  public static InlineKeyboardMarkup getKeyboard(String dayIdentifier, boolean minimalist) {
    InlineKeyboardButton button = new InlineKeyboardButton();
    button.setText(minimalist ? "Расписание неверное" : "⚠️ Расписание неверное/кривое!");
    button.setCallbackData(REPORT_INIT_PREFIX + dayIdentifier);

    return new InlineKeyboardMarkup(List.of(List.of(button)));
  }

  public static InlineKeyboardMarkup getConfirmationKeyboard(
      String dayIdentifier, boolean minimalist) {
    InlineKeyboardButton confirmBtn = new InlineKeyboardButton();
    confirmBtn.setText(minimalist ? "Да, я уверен, отправить" : "🔴 Да, я уверен, отправить");
    confirmBtn.setCallbackData(REPORT_CONFIRM_PREFIX + dayIdentifier);

    InlineKeyboardButton cancelBtn = new InlineKeyboardButton();
    cancelBtn.setText("Отмена");
    cancelBtn.setCallbackData(REPORT_CANCEL + ":" + dayIdentifier);

    return new InlineKeyboardMarkup(List.of(List.of(confirmBtn), List.of(cancelBtn)));
  }
}
