package ru.azenizzka.utils;

import java.util.HashMap;
import java.util.Map;
import ru.azenizzka.exceptions.BellTypeConvertException;
import ru.azenizzka.services.DateService;

public class DayUtil {
  public static final Map<String, Integer> strIntMap = new HashMap<>(7);
  public static final Map<Integer, Day> intDayMap = new HashMap<>(7);
  public static final Map<Day, String> dayStrMap = new HashMap<>(7);

  static {
    strIntMap.put("сегодня", 0);
    strIntMap.put("понедельник", 1);
    strIntMap.put("вторник", 2);
    strIntMap.put("среда", 3);
    strIntMap.put("четверг", 4);
    strIntMap.put("пятница", 5);
    strIntMap.put("суббота", 6);

    intDayMap.put(0, Day.TODAY);
    intDayMap.put(1, Day.MONDAY);
    intDayMap.put(2, Day.TUESDAY);
    intDayMap.put(3, Day.WEDNESDAY);
    intDayMap.put(4, Day.THURSDAY);
    intDayMap.put(5, Day.FRIDAY);
    intDayMap.put(6, Day.SATURDAY);

    dayStrMap.put(Day.TODAY, "сегодня");
    dayStrMap.put(Day.MONDAY, "понедельник");
    dayStrMap.put(Day.TUESDAY, "вторник");
    dayStrMap.put(Day.WEDNESDAY, "среда");
    dayStrMap.put(Day.THURSDAY, "четверг");
    dayStrMap.put(Day.FRIDAY, "пятница");
    dayStrMap.put(Day.SATURDAY, "суббота");
  }

  public static Day convertNumToDay(int num) throws BellTypeConvertException {
    if (intDayMap.containsKey(num)) {
      return intDayMap.get(num);
    } else {
      throw new BellTypeConvertException(MessagesConfig.DAY_INPUT_EXCEPTION);
    }
  }

  public static int convertStrToInt(String str) throws BellTypeConvertException {
    if (str.startsWith("сегодня")) {
      int raw = DateService.getRawDay();
      return (raw < 1 || raw > 6) ? 0 : raw;
    }

    if (str.startsWith("завтра")) {
      int result = DateService.getRawDay() + 1;
      return result > 6 ? 1 : result;
    }

    if (strIntMap.containsKey(str)) {
      return strIntMap.get(str);
    } else {
      throw new BellTypeConvertException(MessagesConfig.DAY_INPUT_EXCEPTION);
    }
  }

  public static String convertDayToStr(Day day) throws BellTypeConvertException {
    if (dayStrMap.containsKey(day)) {
      return dayStrMap.get(day);
    } else {
      throw new BellTypeConvertException(MessagesConfig.DAY_INPUT_EXCEPTION);
    }
  }

  public static Day convertStrToDay(String str) throws BellTypeConvertException {
    return convertNumToDay(convertStrToInt(str));
  }

  public static int convertDayToInt(Day day) throws BellTypeConvertException {
    return convertStrToInt(convertDayToStr(day));
  }
}
