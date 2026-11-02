package ru.azenizzka.services;

import java.time.ZoneId;
import java.time.ZonedDateTime;

public class DateService {
  private static final ZoneId ZONE = ZoneId.of("Asia/Novosibirsk");

  public static int getRawDay() {
    return ZonedDateTime.now(ZONE).getDayOfWeek().getValue();
  }
}
