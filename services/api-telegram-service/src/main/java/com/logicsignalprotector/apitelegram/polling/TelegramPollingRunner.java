package com.logicsignalprotector.apitelegram.polling;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicsignalprotector.apitelegram.client.CommandCenterClient;
import com.logicsignalprotector.apitelegram.client.TelegramBotClient;
import com.logicsignalprotector.apitelegram.model.ChatMessageEnvelope;
import com.logicsignalprotector.apitelegram.model.ChatResponse;
import com.logicsignalprotector.apitelegram.model.InlineKeyboard;
import com.logicsignalprotector.apitelegram.model.OutgoingMessage;
import com.logicsignalprotector.apitelegram.model.RenderMode;
import com.logicsignalprotector.apitelegram.model.UiHints;
import com.logicsignalprotector.apitelegram.model.v2.ChatResponseV2;
import com.logicsignalprotector.apitelegram.render.TelegramRendererV2;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Шаг 1.3-1.5: дополнительный режим длительного опроса Telegram.
 *
 * <p>Полезно для локальной разработки без общедоступного URL-адреса webhook. Включено только в том
 * случае, если telegram.polling.enabled=true.
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "telegram.polling.enabled", havingValue = "true")
public class TelegramPollingRunner {

  private final CommandCenterClient commandCenter;
  private final TelegramBotClient bot;
  private final TelegramRendererV2 rendererV2;
  private final int timeoutSeconds;
  private final AtomicLong offset = new AtomicLong(0);

  public TelegramPollingRunner(
      CommandCenterClient commandCenter,
      TelegramBotClient bot,
      TelegramRendererV2 rendererV2,
      @Value("${telegram.polling.timeout-seconds:20}") int timeoutSeconds) {
    this.commandCenter = commandCenter;
    this.bot = bot;
    this.rendererV2 = rendererV2;
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

        JsonNode callback = upd.path("callback_query");
        if (!callback.isMissingNode() && !callback.isNull()) {
          handleCallback(callback);
          continue;
        }

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
        String locale = message.path("from").path("language_code").asText(null);

        boolean useV2 = isV2Text(text);
        String correlationId = UUID.randomUUID().toString();
        String sessionId = buildSessionId(fromId, chatId);

        ChatMessageEnvelope env =
            new ChatMessageEnvelope(
                "telegram",
                fromId,
                chatId,
                messageId,
                text,
                null,
                correlationId,
                sessionId,
                locale);

        if (useV2) {
          ChatResponseV2 response = commandCenter.sendV2(env);
          sendResponseV2(chatId, messageId, false, response, null);
        } else {
          ChatResponse response = commandCenter.send(env);
          sendResponse(chatId, messageId, false, response);
        }
      }

      // Telegram expects next offset = last_update_id + 1
      offset.set(maxUpdateId + 1);

    } catch (Exception e) {
      log.warn("Telegram polling failed: {}", e.getMessage());
    }
  }

  private void handleCallback(JsonNode callback) {
    JsonNode message = callback.path("message");
    if (message.isMissingNode() || message.isNull()) {
      return;
    }

    String chatId = message.path("chat").path("id").asText();
    String fromId = callback.path("from").path("id").asText();
    String messageId = message.path("message_id").asText(null);
    String callbackId = callback.path("id").asText(null);
    String callbackData = callback.path("data").asText("").trim();
    String locale = callback.path("from").path("language_code").asText(null);

    if (callbackData.isBlank()) {
      return;
    }

    boolean useV2 = isV2Callback(callbackData);
    String correlationId = UUID.randomUUID().toString();
    String sessionId = extractSessionId(callbackData);
    if (sessionId == null || sessionId.isBlank()) {
      sessionId = buildSessionId(fromId, chatId);
    }

    ChatMessageEnvelope env =
        new ChatMessageEnvelope(
            "telegram",
            fromId,
            chatId,
            messageId,
            null,
            callbackData,
            correlationId,
            sessionId,
            locale);

    if (useV2) {
      ChatResponseV2 response = commandCenter.sendV2(env);
      sendResponseV2(chatId, messageId, true, response, callbackData);
    } else {
      ChatResponse response = commandCenter.send(env);
      sendResponse(chatId, messageId, true, response);
    }
    if (callbackId != null && !callbackId.isBlank()) {
      bot.answerCallbackQuery(callbackId);
    }
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

  private void sendResponseV2(
      String chatId,
      String sourceMessageId,
      boolean allowEdit,
      ChatResponseV2 response,
      String callbackData) {
    rendererV2.render(chatId, sourceMessageId, allowEdit, response, callbackData);
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

  private static boolean isV2Text(String text) {
    if (text == null) return false;
    String t = text.trim().toLowerCase(Locale.ROOT);
    return t.startsWith("/help")
        || t.startsWith("/start")
        || t.startsWith("/menu")
        || t.startsWith("/market")
        || t.startsWith("/db")
        || t.startsWith("/помощь")
        || t.startsWith("/меню")
        || t.startsWith("/рынок")
        || t.startsWith("/хелп")
        || t.startsWith("/команды");
  }

  private static boolean isV2Callback(String callbackData) {
    if (callbackData == null) return false;
    String data = callbackData.trim();
    return data.startsWith("h:")
        || data.startsWith("m:")
        || data.startsWith("mi:")
        || data.startsWith("cmd:market")
        || data.startsWith("cmd:menu")
        || data.startsWith("cmd:db");
  }

  private static String extractSessionId(String callbackData) {
    if (callbackData == null) return null;
    String data = callbackData.trim();
    if (data.startsWith("h:") || data.startsWith("m:") || data.startsWith("mi:")) {
      String[] parts = data.split(":", 3);
      if (parts.length >= 2 && !parts[1].isBlank()) {
        return parts[1];
      }
    }
    return null;
  }

  private static String buildSessionId(String fromId, String chatId) {
    String left = fromId == null ? "" : fromId.trim();
    String right = chatId == null ? "" : chatId.trim();
    if (left.isBlank() && right.isBlank()) {
      return UUID.randomUUID().toString();
    }
    return left + "|" + right;
  }
}
