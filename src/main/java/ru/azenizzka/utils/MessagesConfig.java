package ru.azenizzka.utils;

public class MessagesConfig {

  // Навигационные команды (кнопки UI)
  public static final String RETURN_COMMAND = "Назад";
  public static final String RECESS_SCHEDULE_COMMAND = "Звонки";
  public static final String LESSON_SCHEDULE_COMMAND = "Расписание";
  public static final String SETTINGS_COMMAND = "Настройки";
  public static final String CHANGE_GROUP_COMMAND = "Сменить группу";

  // Системные и служебные команды
  public static final String START_COMMAND = "/start";
  public static final String BROADCAST_COMMAND = "/broadcast";
  public static final String SET_ADMIN_COMMAND = "/setadmin";
  public static final String DEL_ADMIN_COMMAND = "/deladmin";
  public static final String WARM_CACHE_COMMAND = "/warm";
  public static final String INFO_COMMAND = "/info";

  // Заголовки и основные сообщения
  public static final String START_MESSAGE =
      "Привет! Чтобы бот мог присылать расписание, просто напиши номер своей группы цифрами прямо в этот чат (например: 232).";
  public static final String BELL_MESSAGE = "🔔 Выберите тип звонков:";
  public static final String DAY_MESSAGE = "📅 Выберите день:";
  public static final String CHANGE_GROUP_MESSAGE = "Введите номер вашей группы (только цифры, например: 232):";
  public static final String SUCCESS_CHANGE_GROUP_MESSAGE = "✅ Группа успешно сохранена";
  public static final String NO_LESSONS_MESSAGE = "🏖 В этот день пар нет!";

  public static final String HELP_MESSAGE =
      """
      • Расписание — график занятий вашей группы
      • Звонки — время начала и конца пар
      • Настройки — смена группы и параметров""";

  public static final String INFO_HEADER = "👥 Пользователей в базе: %d\n\n";
  public static final String ERROR_TEMPLATE = "❌ Ошибка: %s";
  public static final String NOTIFY_TEMPLATE = "🔔 Уведомление:\n%s";

  // Ошибки и исключения
  public static final String BELL_TYPE_CONVERT_EXCEPTION = "Некорректный тип звонков";
  public static final String DAY_INPUT_EXCEPTION = "День указан неверно";
  public static final String UNKNOWN_COMMAND_EXCEPTION = "Неизвестная команда.\n\nВозможные причины:\n- Не выбрана группа: просто отправьте её номер в чат (например, 234).\n- Сбой меню или случайный ввод: отправьте /start для возврата в меню.\n\nЕсли не можете разобраться с ошибкой — напишите @xvycy";
  public static final String GROUP_NOT_FOUND_EXCEPTION = "Группа не найдена на сайте колледжа";
  public static final String GROUP_NOT_DEFINED_EXCEPTION = "Сначала выберите группу в Настройках";
  public static final String NULL_BROADCAST_MESSAGE_EXCEPTION = "Текст сообщения не может быть пустым";
  public static final String PERSON_NOT_FOUND_EXCEPTION = "Пользователь с таким ID не найден";

  // Управление администраторами
  public static final String PERSON_ALREADY_ADMIN = "Пользователь уже администратор";
  public static final String PERSON_ALREADY_NOT_ADMIN = "Пользователь не является администратором";
  public static final String SUCCES_SET_ADMIN = "✅ Администратор успешно назначен";
  public static final String SUCCES_DEL_ADMIN = "✅ Права администратора сняты";
  public static final String YOU_ARE_ADMIN = "⚡ Вам выданы права администратора";
  public static final String YOU_ARE_NOT_ADMIN = "⚡ Вы были сняты с роли администратора";
}
