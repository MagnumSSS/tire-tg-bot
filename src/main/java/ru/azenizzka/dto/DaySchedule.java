package ru.azenizzka.dto;

import java.util.List;
import java.util.Objects;
import ru.azenizzka.utils.Day;

/**
 * DTO-заглушка расписания занятий группы на конкретный учебный день.
 *
 * @param group учебная группа.
 * @param day день недели.
 * @param lessons список занятий.
 */
public record DaySchedule(Group group, Day day, List<Lesson> lessons) {

  public DaySchedule {
    lessons = lessons != null ? List.copyOf(lessons) : List.of();
  }

  public DaySchedule(Day day, List<Lesson> lessons) {
    this(new Group(""), day, lessons);
  }

  public boolean hasLessons() {
    return !Objects.requireNonNullElse(lessons, List.of()).isEmpty();
  }
}
