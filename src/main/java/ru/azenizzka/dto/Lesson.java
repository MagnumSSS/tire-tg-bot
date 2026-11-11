package ru.azenizzka.dto;

import java.util.Objects;
import java.util.Optional;

/**
 * DTO-заглушка учебного занятия (пары).
 *
 * @param pairNumber порядковый номер пары (1-6, 0 если не определен).
 * @param subject наименование дисциплины.
 * @param room номер аудитории или признак дистанта.
 * @param teacher ФИО преподавателя (если удалось извлечь).
 * @param subgroup номер подгруппы (1, 2) или null, если занятие для всей группы.
 * @param isReplacement признак замены/изменения в расписании.
 */
public record Lesson(
    int pairNumber,
    String subject,
    String room,
    String teacher,
    Integer subgroup,
    boolean isReplacement) {

  public Lesson {
    subject = Objects.requireNonNullElse(subject, "").trim();
    room = Objects.requireNonNullElse(room, "").trim();
    teacher = Objects.requireNonNullElse(teacher, "").trim();
  }

  public Optional<Integer> subgroupOpt() {
    return Optional.ofNullable(subgroup);
  }

  public Optional<String> teacherOpt() {
    return teacher.isBlank() ? Optional.empty() : Optional.of(teacher);
  }
}
