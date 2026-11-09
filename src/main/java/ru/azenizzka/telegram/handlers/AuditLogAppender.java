package ru.azenizzka.telegram.handlers;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import java.util.concurrent.CompletableFuture;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import ru.azenizzka.telegram.TelegramBot;

public class AuditLogAppender extends AppenderBase<ILoggingEvent> {

  private static final ThreadLocal<Boolean> IS_SENDING = ThreadLocal.withInitial(() -> false);

  @Override
  protected void append(ILoggingEvent event) {
    if (event == null || !isStarted()) {
      return;
    }

    if (IS_SENDING.get()) {
      return;
    }

    if (!event.getLevel().isGreaterOrEqual(Level.WARN)) {
      return;
    }

    String loggerName = event.getLoggerName();
    if (loggerName != null
        && (loggerName.startsWith("org.telegram")
            || loggerName.startsWith("org.apache.http")
            || loggerName.contains("AuditLogAppender"))) {
      return;
    }

    TelegramBot bot = TelegramBot.getInstance();
    if (bot == null) {
      return;
    }

    String auditChatId = bot.getAuditLogChatId();
    if (auditChatId == null || auditChatId.isBlank()) {
      return;
    }

    String simpleLogger =
        loggerName != null ? loggerName.substring(loggerName.lastIndexOf('.') + 1) : "App";
    String emoji = event.getLevel() == Level.ERROR ? "🚨" : "⚠️";
    StringBuilder sb = new StringBuilder();
    sb.append(emoji)
        .append(" [")
        .append(event.getLevel())
        .append("] ")
        .append(simpleLogger)
        .append(":\n")
        .append(event.getFormattedMessage());

    if (event.getThrowableProxy() != null) {
      String tp = ThrowableProxyUtil.asString(event.getThrowableProxy());
      if (tp != null && !tp.isBlank()) {
        if (tp.length() > 1500) {
          tp = tp.substring(0, 1500) + "...";
        }
        sb.append("\n\n").append(tp);
      }
    }

    String text = sb.toString();
    if (text.length() > 4000) {
      text = text.substring(0, 4000) + "...";
    }

    final String messageText = text;

    CompletableFuture.runAsync(
        () -> {
          IS_SENDING.set(true);
          try {
            SendMessage sendMessage = new SendMessage(auditChatId, messageText);
            sendMessage.enableMarkdown(false);
            bot.execute(sendMessage);
          } catch (Exception ignored) {
          } finally {
            IS_SENDING.set(false);
          }
        });
  }
}
