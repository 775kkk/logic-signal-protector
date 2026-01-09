package com.logicsignalprotector.apitelegram.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicsignalprotector.apitelegram.client.CommandCenterClient;
import com.logicsignalprotector.apitelegram.client.TelegramBotClient;
import com.logicsignalprotector.apitelegram.model.ChatMessageEnvelope;
import com.logicsignalprotector.apitelegram.model.ChatResponse;
import com.logicsignalprotector.apitelegram.model.InlineKeyboard;
import com.logicsignalprotector.apitelegram.model.OutgoingMessage;
import com.logicsignalprotector.apitelegram.model.RenderMode;
import com.logicsignalprotector.apitelegram.model.UiHints;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Step 1.3-1.5: Telegram webhook endpoint.
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

    JsonNode callback = update.path("callback_query");
    if (!callback.isMissingNode() && !callback.isNull()) {
      return handleCallback(callback);
    }

    JsonNode message = update.path("message");
    if (message.isMissingNode() || message.isNull()) {
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

    ChatMessageEnvelope env =
        new ChatMessageEnvelope("telegram", fromId, chatId, messageId, text, null);

    ChatResponse response = commandCenter.send(env);
    sendResponse(chatId, messageId, false, response);

    return Map.of("ok", true, "sent", response == null ? 0 : response.messages().size());
  }

  private Map<String, Object> handleCallback(JsonNode callback) {
    JsonNode message = callback.path("message");
    if (message.isMissingNode() || message.isNull()) {
      return Map.of("ok", true, "ignored", "no_message");
    }

    String chatId = message.path("chat").path("id").asText();
    String fromId = callback.path("from").path("id").asText();
    String messageId = message.path("message_id").asText(null);
    String callbackId = callback.path("id").asText(null);
    String callbackData = callback.path("data").asText("").trim();

    if (callbackData.isBlank()) {
      return Map.of("ok", true, "ignored", "blank_callback");
    }

    ChatMessageEnvelope env =
        new ChatMessageEnvelope("telegram", fromId, chatId, messageId, null, callbackData);

    ChatResponse response = commandCenter.send(env);
    sendResponse(chatId, messageId, true, response);
    if (callbackId != null && !callbackId.isBlank()) {
      bot.answerCallbackQuery(callbackId);
    }

    return Map.of("ok", true, "sent", response == null ? 0 : response.messages().size());
  }

  private void sendResponse(
      String chatId, String sourceMessageId, boolean allowEdit, ChatResponse response) {
    if (response == null || response.messages() == null) {
      return;
    }

    boolean deleteRequested = false;
    for (OutgoingMessage m : response.messages()) {
      if (m == null || m.text() == null || m.text().isBlank()) {
        continue;
      }

      UiHints hints = m.uiHints();
      boolean preferEdit = hints != null && hints.preferEdit();
      boolean deleteSource = hints != null && hints.deleteSourceMessage();
      InlineKeyboard keyboard = hints == null ? null : hints.inlineKeyboard();

      String renderedText = renderText(m.text(), hints);
      String parseMode = hints == null ? null : hints.parseModeHint();
      if (hints != null
          && hints.renderMode() == RenderMode.PRE
          && (parseMode == null || parseMode.isBlank())) {
        parseMode = "HTML";
      }

      if (preferEdit && allowEdit && sourceMessageId != null) {
        bot.editMessageText(chatId, sourceMessageId, renderedText, parseMode, keyboard);
      } else {
        bot.sendMessage(chatId, renderedText, parseMode, keyboard);
      }

      deleteRequested = deleteRequested || deleteSource;
    }

    if (deleteRequested && sourceMessageId != null) {
      bot.deleteMessage(chatId, sourceMessageId);
    }
  }

  private static String renderText(String text, UiHints hints) {
    if (hints == null || hints.renderMode() == null) {
      return text;
    }
    if (hints.renderMode() != RenderMode.PRE) {
      return text;
    }
    return "<pre>" + escapeHtml(text) + "</pre>";
  }

  private static String escapeHtml(String s) {
    if (s == null) return "";
    String out = s;
    out = out.replace("&", "&amp;");
    out = out.replace("<", "&lt;");
    out = out.replace(">", "&gt;");
    return out;
  }
}
