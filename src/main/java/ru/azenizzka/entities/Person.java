package ru.azenizzka.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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

  @Column(columnDefinition = "boolean default false")
  private boolean isAdmin = false;

  private InputType inputType;

  @Column(columnDefinition = "boolean default false")
  private boolean isBanned = false;

  private Long banUntil;
  private String banReason;

  @Column(columnDefinition = "boolean default false")
  private boolean isReportMuted = false;

  @Column(columnDefinition = "boolean default false")
  private boolean isMinimalistMode = false;

  @Column(columnDefinition = "boolean default false")
  private boolean notificationsEnabled = false;

  @Column(columnDefinition = "boolean default false")
  private boolean compactMenu = false;

  private Integer pinnedMessageId;
}
