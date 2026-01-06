package com.logicsignalprotector.apitelegram.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicsignalprotector.apitelegram.client.CommandCenterClient;
import com.logicsignalprotector.apitelegram.client.TelegramBotClient;
import com.logicsignalprotector.apitelegram.model.ChatMessageEnvelope;
import com.logicsignalprotector.apitelegram.model.ChatResponse;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Step 1.3: Telegram webhook endpoint.
 *
 * <p>To make it work you need a public URL and to set a webhook in Telegram. For local learning,
 * use {@link DevTelegramController}.
 */
@RestController
@RequestMapping("/telegram")
@Slf4j
public class TelegramWebhookController {

  private final CommandCenterClient commandCenter;
  private final TelegramBotClient bot;
  private final String secretToken;

  public TelegramWebhookController(
      CommandCenterClient commandCenter,
      TelegramBotClient bot,
      @Value("${telegram.webhook.secret-token:}") String secretToken) {
    this.commandCenter = commandCenter;
    this.bot = bot;
    this.secretToken = secretToken == null ? "" : secretToken.trim();
  }

  @PostMapping("/webhook")
  public Map<String, Object> webhook(
      @RequestBody JsonNode update,
      @RequestHeader(value = "X-Telegram-Bot-Api-Secret-Token", required = false)
          String headerSecret) {

    if (!secretToken.isBlank()) {
      if (headerSecret == null || !secretToken.equals(headerSecret)) {
        log.warn("Webhook secret token mismatch");
        return Map.of("ok", false);
      }
    }

    JsonNode message = update.path("message");
    if (message.isMissingNode() || message.isNull()) {
      // Ignore non-message updates for now (callback_query, inline_query, ...)
      return Map.of("ok", true, "ignored", "no_message");
    }

    JsonNode textNode = message.path("text");
    if (textNode.isMissingNode() || textNode.isNull()) {
      return Map.of("ok", true, "ignored", "no_text");
    }

    String text = textNode.asText("").trim();
    if (text.isBlank()) {
      return Map.of("ok", true, "ignored", "blank_text");
    }

    String chatId = message.path("chat").path("id").asText();
    String fromId = message.path("from").path("id").asText();
    String messageId = message.path("message_id").asText(null);

    ChatMessageEnvelope env = new ChatMessageEnvelope("telegram", fromId, chatId, messageId, text);

    ChatResponse response = commandCenter.send(env);
    if (response != null && response.messages() != null) {
      for (var m : response.messages()) {
        if (m != null && m.text() != null && !m.text().isBlank()) {
          bot.sendMessage(chatId, m.text());
        }
      }
    }

    return Map.of("ok", true, "sent", response == null ? 0 : response.messages().size());
  }
}
