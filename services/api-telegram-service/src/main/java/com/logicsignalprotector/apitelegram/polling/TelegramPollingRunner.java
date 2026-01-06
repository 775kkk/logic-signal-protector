package com.logicsignalprotector.apitelegram.polling;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicsignalprotector.apitelegram.client.CommandCenterClient;
import com.logicsignalprotector.apitelegram.client.TelegramBotClient;
import com.logicsignalprotector.apitelegram.model.ChatMessageEnvelope;
import com.logicsignalprotector.apitelegram.model.ChatResponse;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Step 1.3: optional Telegram long-polling mode.
 *
 * <p>Useful for local development without a public webhook URL. Enabled only when
 * telegram.polling.enabled=true.
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "telegram.polling.enabled", havingValue = "true")
public class TelegramPollingRunner {

  private final CommandCenterClient commandCenter;
  private final TelegramBotClient bot;
  private final int timeoutSeconds;
  private final AtomicLong offset = new AtomicLong(0);

  public TelegramPollingRunner(
      CommandCenterClient commandCenter,
      TelegramBotClient bot,
      @Value("${telegram.polling.timeout-seconds:20}") int timeoutSeconds) {
    this.commandCenter = commandCenter;
    this.bot = bot;
    this.timeoutSeconds = timeoutSeconds;
  }

  @Scheduled(fixedDelayString = "${telegram.polling.fixed-delay-ms:2000}")
  public void poll() {
    if (!bot.isConfigured()) {
      log.warn("telegram.polling.enabled=true but TELEGRAM_BOT_TOKEN is empty; polling is skipped");
      return;
    }

    try {
      JsonNode resp = bot.getUpdates(offset.get(), timeoutSeconds);
      if (resp == null) return;

      JsonNode result = resp.path("result");
      if (!result.isArray() || result.isEmpty()) return;

      long maxUpdateId = offset.get() - 1;

      for (JsonNode upd : result) {
        long updateId = upd.path("update_id").asLong(-1);
        if (updateId > maxUpdateId) maxUpdateId = updateId;

        JsonNode message = upd.path("message");
        if (message.isMissingNode() || message.isNull()) {
          continue;
        }

        JsonNode textNode = message.path("text");
        if (textNode.isMissingNode() || textNode.isNull()) {
          continue;
        }

        String text = textNode.asText("").trim();
        if (text.isBlank()) {
          continue;
        }

        String chatId = message.path("chat").path("id").asText();
        String fromId = message.path("from").path("id").asText();
        String messageId = message.path("message_id").asText(null);

        ChatMessageEnvelope env =
            new ChatMessageEnvelope("telegram", fromId, chatId, messageId, text);

        ChatResponse response = commandCenter.send(env);
        if (response != null && response.messages() != null) {
          for (var m : response.messages()) {
            if (m != null && m.text() != null && !m.text().isBlank()) {
              bot.sendMessage(chatId, m.text());
            }
          }
        }
      }

      // Telegram expects next offset = last_update_id + 1
      offset.set(maxUpdateId + 1);

    } catch (Exception e) {
      log.warn("Telegram polling failed: {}", e.getMessage());
    }
  }
}
