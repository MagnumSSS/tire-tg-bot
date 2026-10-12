package ru.azenizzka.telegram.handlers;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import ru.azenizzka.configuration.TelegramBotConfiguration;
import ru.azenizzka.entities.Person;
import ru.azenizzka.telegram.TelegramBot;
import ru.azenizzka.telegram.commands.SettingsCommand;
import ru.azenizzka.telegram.keyboards.MainKeyboard;
import ru.azenizzka.telegram.keyboards.ReportKeyboard;
import ru.azenizzka.telegram.messages.CustomMessage;

@Component
@Slf4j
public class CallbackQueryHandler {

  private final TelegramBotConfiguration configuration;
  private final TelegramBot telegramBot;

  private final Map<Long, Long> cooldownMap = new ConcurrentHashMap<>();
  private static final long COOLDOWN_MS = 5 * 60 * 1000;

  private static final String WARNING_TEXT =
      "Вы точно уверены?\n\n"
          + "Если вы нажмете на подтверждение, а расписание верное, "
          + "то вы будете временно заблокированы для репортов. "
          + "За повторные нарушения возможна вечная блокировка в боте.";

  public CallbackQueryHandler(
      TelegramBotConfiguration configuration,
      @Lazy TelegramBot telegramBot) {
    this.configuration = configuration;
    this.telegramBot = telegramBot;
  }

  public void handle(CallbackQuery callbackQuery, Person person) {
    String data = callbackQuery.getData();
    if (data == null || person == null) return;

    if (data.equals(SettingsCommand.TOGGLE_NOTIFY_CALLBACK)) {
      handleToggleNotify(callbackQuery, person);
    } else if (data.equals(SettingsCommand.TOGGLE_MENU_CALLBACK)) {
      handleToggleMenu(callbackQuery, person);
    } else if (data.startsWith(ReportKeyboard.REPORT_INIT_PREFIX)) {
      handleInitialClick(callbackQuery);
    } else if (data.startsWith(ReportKeyboard.REPORT_CONFIRM_PREFIX)) {
      handleConfirmation(callbackQuery, person);
    } else if (data.startsWith(ReportKeyboard.REPORT_CANCEL)) {
      handleCancel(callbackQuery);
    }
  }

  private void handleToggleNotify(CallbackQuery query, Person person) {
    person.setNotificationsEnabled(!person.isNotificationsEnabled());

    updateSettingsMessage(query, person);
    answer(query.getId(), person.isNotificationsEnabled() ? "🔔 Уведомления включены" : "🔕 Уведомления выключены", false);
  }

  private void handleToggleMenu(CallbackQuery query, Person person) {
    person.setCompactMenu(!person.isCompactMenu());

    updateSettingsMessage(query, person);
    answer(query.getId(), "Вид главного меню обновлен!", false);

    CustomMessage kbUpdate = new CustomMessage(person.getChatId(), person.isCompactMenu()
        ? "📱 Включено *компактное меню*."
        : "📅 Включено *полное меню* со всеми днями недели.");
    MainKeyboard.addKeyboard(kbUpdate, person.isCompactMenu());
    try {
      telegramBot.execute(kbUpdate);
    } catch (TelegramApiException ignored) {
    }
  }

  private void updateSettingsMessage(CallbackQuery query, Person person) {
    try {
      EditMessageText edit = new EditMessageText();
      edit.setChatId(query.getMessage().getChatId().toString());
      edit.setMessageId(query.getMessage().getMessageId());
      edit.setText(SettingsCommand.buildText(person));
      edit.enableMarkdown(true);
      edit.setReplyMarkup(SettingsCommand.buildInlineKeyboard(person));
      telegramBot.execute(edit);
    } catch (TelegramApiException ignored) {
    }
  }

  private void handleInitialClick(CallbackQuery callbackQuery) {
    long userId = callbackQuery.getFrom().getId();
    long now = System.currentTimeMillis();

    if (cooldownMap.containsKey(userId) && (now - cooldownMap.get(userId) < COOLDOWN_MS)) {
      long waitSec = (COOLDOWN_MS - (now - cooldownMap.get(userId))) / 1000;
      answer(callbackQuery.getId(), "⚠️ Вы уже отправляли жалобу. Подождите " + waitSec + " сек.", true);
      return;
    }

    String day = callbackQuery.getData().substring(ReportKeyboard.REPORT_INIT_PREFIX.length());
    answer(callbackQuery.getId(), WARNING_TEXT, true);
    editMarkup(callbackQuery, ReportKeyboard.getConfirmationKeyboard(day));
  }

  private void handleConfirmation(CallbackQuery callbackQuery, Person person) {
    long userId = callbackQuery.getFrom().getId();
    long now = System.currentTimeMillis();

    if (cooldownMap.containsKey(userId) && (now - cooldownMap.get(userId) < COOLDOWN_MS)) {
      long waitSec = (COOLDOWN_MS - (now - cooldownMap.get(userId))) / 1000;
      answer(callbackQuery.getId(), "⚠️ Вы уже отправляли жалобу. Подождите " + waitSec + " сек.", true);
      return;
    }

    cooldownMap.put(userId, now);

    String day = callbackQuery.getData().substring(ReportKeyboard.REPORT_CONFIRM_PREFIX.length());
    String group = (person.getGroupNum() != 0) ? String.valueOf(person.getGroupNum()) : "Не указана";
    String username = callbackQuery.getFrom().getUserName() != null ? "@" + callbackQuery.getFrom().getUserName() : "нет username";

    String alertMessage = String.format(
        "🚨 *Подтвержденная жалоба на расписание!*\n"
            + "👤 Пользователь: %s (`%d`)\n"
            + "👥 Группа: `%s` \n"
            + "📅 День: `%s`",
        username, userId, group, day);

    try {
      SendMessage sendAlert = new SendMessage(configuration.getAuditLogChatId(), alertMessage);
      sendAlert.enableMarkdown(true);
      telegramBot.execute(sendAlert);
    } catch (TelegramApiException e) {
      log.error("Ошибка при отправке алерта в аудит-чат: ", e);
    }

    answer(callbackQuery.getId(), "Жалоба отправлена разработчику!", false);
    editMarkup(callbackQuery, null);
  }

  private void handleCancel(CallbackQuery callbackQuery) {
    String[] parts = callbackQuery.getData().split(":", 2);
    String day = parts.length > 1 ? parts[1] : "0";

    answer(callbackQuery.getId(), "Отправка отменена", false);
    editMarkup(callbackQuery, ReportKeyboard.getKeyboard(day));
  }

  private void editMarkup(CallbackQuery query, org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup markup) {
    try {
      EditMessageReplyMarkup edit = new EditMessageReplyMarkup();
      edit.setChatId(query.getMessage().getChatId().toString());
      edit.setMessageId(query.getMessage().getMessageId());
      edit.setReplyMarkup(markup);
      telegramBot.execute(edit);
    } catch (TelegramApiException ignored) {
    }
  }

  private void answer(String queryId, String text, boolean alert) {
    try {
      AnswerCallbackQuery answer = new AnswerCallbackQuery();
      answer.setCallbackQueryId(queryId);
      answer.setText(text);
      answer.setShowAlert(alert);
      telegramBot.execute(answer);
    } catch (TelegramApiException e) {
      log.error("Ошибка AnswerCallbackQuery: ", e);
    }
  }
}
