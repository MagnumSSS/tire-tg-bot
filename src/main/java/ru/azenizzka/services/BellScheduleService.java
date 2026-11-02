package ru.azenizzka.services;

import java.util.Map;
import org.springframework.stereotype.Service;
import ru.azenizzka.utils.BellType;
import ru.azenizzka.utils.Day;

@Service
public class BellScheduleService {

  private static final byte[][][] BELL_MAIN = {
    {{8, 30}, {9, 15}, {9, 20}, {10, 5}},
    {{10, 20}, {11, 5}, {11, 10}, {11, 55}},
    {{12, 25}, {13, 10}, {13, 15}, {14, 0}},
    {{14, 30}, {15, 15}, {15, 20}, {16, 5}},
    {{16, 15}, {17, 0}, {17, 5}, {17, 50}},
    {{18, 0}, {18, 45}, {18, 50}, {19, 35}}
  };

  private static final byte[][][] BELL_SATURDAY = {
    {{8, 30}, {9, 15}, {9, 20}, {10, 5}},
    {{10, 20}, {11, 5}, {11, 10}, {11, 55}},
    {{12, 5}, {12, 50}, {12, 55}, {13, 40}},
    {{13, 50}, {14, 35}, {14, 40}, {15, 25}},
    {{15, 35}, {16, 20}, {16, 25}, {17, 10}},
    {{17, 20}, {18, 5}, {18, 10}, {18, 55}}
  };

  private static final byte[][][] BELL_MONDAY = {
    {{9, 20}, {10, 5}, {10, 10}, {10, 55}},
    {{11, 15}, {12, 0}, {12, 5}, {12, 50}},
    {{13, 20}, {14, 5}, {14, 10}, {14, 55}},
    {{15, 15}, {16, 0}, {16, 5}, {16, 50}},
    {{17, 0}, {17, 45}, {17, 50}, {18, 35}},
    {{18, 45}, {19, 30}, {19, 35}, {20, 20}}
  };

  private static final String TIME_SEPARATOR = ":";
  private static final String SPACE = " ";
  private static final String PARTS_SEPARATOR = "    ";
  private static final String END_OF_LINE = "\n";

  private static final Map<Integer, String> MONDAY_BELLS =
      Map.of(
          1, "09:20–10:05 | 10:10–10:55",
          2, "11:15–12:00 | 12:05–12:50",
          3, "13:20–14:05 | 14:10–14:55",
          4, "15:15–16:00 | 16:05–16:50",
          5, "17:00–17:45 | 17:50–18:35",
          6, "18:45–19:30 | 19:35–20:20");

  private static final Map<Integer, String> SATURDAY_BELLS =
      Map.of(
          1, "08:30–09:15 | 09:20–10:05",
          2, "10:20–11:05 | 11:10–11:55",
          3, "12:05–12:50 | 12:55–13:40",
          4, "13:50–14:35 | 14:40–15:25",
          5, "15:35–16:20 | 16:25–17:10",
          6, "17:20–18:05 | 18:10–18:55");

  private static final Map<Integer, String> DEFAULT_BELLS =
      Map.of(
          1, "08:30–09:15 | 09:20–10:05",
          2, "10:20–11:05 | 11:10–11:55",
          3, "12:25–13:10 | 13:15–14:00",
          4, "14:30–15:15 | 15:20–16:05",
          5, "16:15–17:00 | 17:05–17:50",
          6, "18:00–18:45 | 18:50–19:35");

  public String getBellTime(Day day, int lessonNum) {
    Map<Integer, String> targetMap =
        switch (day) {
          case MONDAY -> MONDAY_BELLS;
          case SATURDAY -> SATURDAY_BELLS;
          default -> DEFAULT_BELLS;
        };

    return targetMap.getOrDefault(lessonNum, "");
  }

  public static String getStringWithSchedule(BellType bellType) {
    StringBuilder out = new StringBuilder();
    byte[][][] bellRecess;

    switch (bellType) {
      case MAIN -> {
        out.append("*Основное* расписание звонков\n\n");
        bellRecess = BELL_MAIN;
      }
      case MONDAY -> {
        out.append("Расписание звонков на *Понедельник*\n\n");
        bellRecess = BELL_MONDAY;
      }
      case SATURDAY -> {
        out.append("Расписание звонков на *Субботу*\n\n");
        bellRecess = BELL_SATURDAY;
      }
      default -> bellRecess = BELL_MAIN;
    }

    for (int lesson = 0; lesson < 6; lesson++) {
      out.append("*").append(lesson + 1).append(" пара:*\n");
      for (int time = 0; time < 4; time++) {
        String from = String.valueOf(bellRecess[lesson][time][0]);
        String to = String.valueOf(bellRecess[lesson][time][1]);

        if (from.length() == 1) {
          from = "0" + from;
        }
        if (to.length() == 1) {
          to = "0" + to;
        }

        out.append(from).append(TIME_SEPARATOR).append(to);

        if (time == 0 || time == 2) {
          out.append(SPACE);
        } else if (time == 1) {
          out.append(PARTS_SEPARATOR);
        } else {
          out.append(END_OF_LINE);
        }
      }
      out.append(END_OF_LINE);
    }

    return out.toString();
  }
}
