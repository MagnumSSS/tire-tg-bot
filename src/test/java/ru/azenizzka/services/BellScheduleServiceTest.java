package ru.azenizzka.services;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import ru.azenizzka.utils.BellType;
import ru.azenizzka.utils.Day;

class BellScheduleServiceTest {

  private final BellScheduleService bellService = new BellScheduleService();

  @Test
  void testGetBellTimeForMonday() {
    String time = bellService.getBellTime(Day.MONDAY, 1);
    assertFalse(time.isBlank(), "Расписание на 1 пару понедельника не должно быть пустым");
    assertTrue(time.contains("09:20"), "Первая пара понедельника должна начинаться в 09:20");
  }

  @Test
  void testScheduleFormattingOutput() {
    String output = BellScheduleService.getStringWithSchedule(BellType.MAIN);
    assertTrue(output.contains("1 пара:"), "В выводе должна присутствовать нумерация пар");
    assertTrue(output.contains("08:30"), "Основное расписание должно начинаться в 08:30");
  }
}
