package ru.azenizzka.entities;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import ru.azenizzka.telegram.handlers.InputType;

@NoArgsConstructor
@Getter
@Setter
@Entity
public class Person {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private int id;

  private String chatId;
  private int groupNum;
  private String username;
  private boolean isAdmin;
  private InputType inputType;

  private boolean isBanned = false;
  private boolean isReportMuted = false;

  @Column(columnDefinition = "boolean default true")
  private boolean notificationsEnabled = true;

  @Column(columnDefinition = "boolean default false")
  private boolean compactMenu = false;
}
