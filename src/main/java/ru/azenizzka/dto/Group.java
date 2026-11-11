package ru.azenizzka.dto;

import java.util.Objects;

/**
 * DTO-заглушка учебной группы.
 *
 * @param name нормализованное наименование группы (например, "С-111", "МР-231").
 */
public record Group(String name) {

  public Group {
    name = Objects.requireNonNullElse(name, "").trim();
  }

  @Override
  public String toString() {
    return name;
  }
}
