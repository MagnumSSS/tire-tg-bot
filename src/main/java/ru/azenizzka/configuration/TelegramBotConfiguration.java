package ru.azenizzka.configuration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import ru.azenizzka.telegram.TelegramBot;

@Configuration
@Getter
@Setter
public class TelegramBotConfiguration {

  @Value("${bot.name}")
  private String name;

  @Value("${bot.token}")
  private String token;

  @Value("${bot.adminChatId:}")
  private String adminChatId;

  @Value("${bot.auditLogChatId:}")
  private String auditLogChatId;

  @Value("${bot.test-mode:false}")
  private boolean testMode;

  @Bean
  public TelegramBotsApi telegramBotsApi(TelegramBot telegramBot) throws TelegramApiException {
    TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
    botsApi.registerBot(telegramBot);
    return botsApi;
  }
}
