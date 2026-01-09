package com.logicsignalprotector.apitelegram.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicsignalprotector.apitelegram.model.InlineKeyboard;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
@Slf4j
public class TelegramBotClient {

  private final RestClient rest;
  private final String botToken;

  public TelegramBotClient(
      RestClient.Builder builder, @Value("${telegram.bot-token:}") String botToken) {
    this.botToken = botToken == null ? "" : botToken.trim();
    this.rest = builder.build();
  }

  public boolean isConfigured() {
    return !botToken.isBlank();
  }

  public JsonNode getUpdates(long offset, int timeoutSeconds) {
    if (!isConfigured()) {
      log.warn("Telegram bot token is not configured; skip getUpdates");
      return null;
    }

    String url = "https://api.telegram.org/bot" + botToken + "/getUpdates";
    try {
      return rest.get()
          .uri(url + "?offset={offset}&timeout={timeout}", offset, timeoutSeconds)
          .retrieve()
          .body(JsonNode.class);
    } catch (Exception e) {
      log.warn("Failed to call getUpdates: {}", e.getMessage());
      return null;
    }
  }

  public void sendMessage(String chatId, String text) {
    sendMessage(chatId, text, null, null);
  }

  public void sendMessage(String chatId, String text, String parseMode, InlineKeyboard keyboard) {
    if (!isConfigured()) {
      log.warn("Telegram bot token is not configured; skip sending message to chatId={}", chatId);
      return;
    }

    String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
    try {
      Map<String, Object> body = new HashMap<>();
      body.put("chat_id", chatId);
      body.put("text", text);
      if (parseMode != null && !parseMode.isBlank()) {
        body.put("parse_mode", parseMode);
      }
      if (keyboard != null) {
        body.put("reply_markup", toInlineKeyboard(keyboard));
      }
      rest.post().uri(url).body(body).retrieve().toBodilessEntity();
    } catch (Exception e) {
      log.warn("Failed to send Telegram message: {}", e.getMessage());
    }
  }

  public void editMessageText(
      String chatId, String messageId, String text, String parseMode, InlineKeyboard keyboard) {
    if (!isConfigured()) {
      log.warn("Telegram bot token is not configured; skip editMessageText");
      return;
    }
    if (messageId == null || messageId.isBlank()) {
      return;
    }

    String url = "https://api.telegram.org/bot" + botToken + "/editMessageText";
    try {
      Map<String, Object> body = new HashMap<>();
      body.put("chat_id", chatId);
      body.put("message_id", messageId);
      body.put("text", text);
      if (parseMode != null && !parseMode.isBlank()) {
        body.put("parse_mode", parseMode);
      }
      if (keyboard != null) {
        body.put("reply_markup", toInlineKeyboard(keyboard));
      }
      rest.post().uri(url).body(body).retrieve().toBodilessEntity();
    } catch (Exception e) {
      log.warn("Failed to edit Telegram message: {}", e.getMessage());
    }
  }

  public void deleteMessage(String chatId, String messageId) {
    if (!isConfigured()) {
      log.warn("Telegram bot token is not configured; skip deleteMessage");
      return;
    }
    if (messageId == null || messageId.isBlank()) {
      return;
    }
    String url = "https://api.telegram.org/bot" + botToken + "/deleteMessage";
    try {
      rest.post()
          .uri(url)
          .body(Map.of("chat_id", chatId, "message_id", messageId))
          .retrieve()
          .toBodilessEntity();
    } catch (Exception e) {
      log.warn("Failed to delete Telegram message: {}", e.getMessage());
    }
  }

  public void answerCallbackQuery(String callbackQueryId) {
    if (!isConfigured()) {
      log.warn("Telegram bot token is not configured; skip answerCallbackQuery");
      return;
    }
    if (callbackQueryId == null || callbackQueryId.isBlank()) {
      return;
    }
    String url = "https://api.telegram.org/bot" + botToken + "/answerCallbackQuery";
    try {
      rest.post()
          .uri(url)
          .body(Map.of("callback_query_id", callbackQueryId))
          .retrieve()
          .toBodilessEntity();
    } catch (Exception e) {
      log.warn("Failed to answer callback query: {}", e.getMessage());
    }
  }

  private static Map<String, Object> toInlineKeyboard(InlineKeyboard keyboard) {
    var rows = new java.util.ArrayList<java.util.List<Map<String, Object>>>();
    for (var row : keyboard.rows()) {
      var outRow = new java.util.ArrayList<Map<String, Object>>();
      if (row != null) {
        for (var btn : row) {
          if (btn == null) continue;
          var b = new HashMap<String, Object>();
          b.put("text", btn.text());
          b.put("callback_data", btn.callbackData());
          outRow.add(b);
        }
      }
      rows.add(outRow);
    }
    Map<String, Object> out = new HashMap<>();
    out.put("inline_keyboard", rows);
    return out;
  }
}
