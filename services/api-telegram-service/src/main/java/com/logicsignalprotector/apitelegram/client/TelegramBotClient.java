package com.logicsignalprotector.apitelegram.client;

import com.fasterxml.jackson.databind.JsonNode;
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
    if (!isConfigured()) {
      log.warn("Telegram bot token is not configured; skip sending message to chatId={}", chatId);
      return;
    }

    String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
    try {
      rest.post()
          .uri(url)
          .body(Map.of("chat_id", chatId, "text", text))
          .retrieve()
          .toBodilessEntity();
    } catch (Exception e) {
      log.warn("Failed to send Telegram message: {}", e.getMessage());
    }
  }
}
