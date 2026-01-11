package com.logicsignalprotector.apitelegram.api;

import com.logicsignalprotector.apitelegram.client.CommandCenterClient;
import com.logicsignalprotector.apitelegram.model.ChatMessageEnvelope;
import com.logicsignalprotector.apitelegram.model.ChatResponse;
import com.logicsignalprotector.apitelegram.model.v2.ChatResponseV2;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Step 1.3: local training endpoint.
 *
 * <p>Allows sending "fake" Telegram messages without real Telegram webhook.
 */
@RestController
@RequestMapping("/dev/telegram")
public class DevTelegramController {

  private final CommandCenterClient commandCenter;

  public DevTelegramController(CommandCenterClient commandCenter) {
    this.commandCenter = commandCenter;
  }

  public record DevMessageRequest(
      @NotNull Long telegramUserId, @NotNull Long chatId, @NotBlank String text) {}

  @PostMapping("/message")
  public ChatResponse message(@Valid @RequestBody DevMessageRequest req) {
    String correlationId = UUID.randomUUID().toString();
    String sessionId = buildSessionId(req.telegramUserId(), req.chatId());
    ChatMessageEnvelope env =
        new ChatMessageEnvelope(
            "telegram",
            String.valueOf(req.telegramUserId()),
            String.valueOf(req.chatId()),
            null,
            req.text(),
            null,
            correlationId,
            sessionId,
            null);
    return commandCenter.send(env);
  }

  @PostMapping("/message/v2")
  public ChatResponseV2 messageV2(@Valid @RequestBody DevMessageRequest req) {
    String correlationId = UUID.randomUUID().toString();
    String sessionId = buildSessionId(req.telegramUserId(), req.chatId());
    ChatMessageEnvelope env =
        new ChatMessageEnvelope(
            "telegram",
            String.valueOf(req.telegramUserId()),
            String.valueOf(req.chatId()),
            null,
            req.text(),
            null,
            correlationId,
            sessionId,
            null);
    return commandCenter.sendV2(env);
  }

  private static String buildSessionId(Long telegramUserId, Long chatId) {
    String left = telegramUserId == null ? "" : String.valueOf(telegramUserId);
    String right = chatId == null ? "" : String.valueOf(chatId);
    if (left.isBlank() && right.isBlank()) {
      return UUID.randomUUID().toString();
    }
    return left + "|" + right;
  }
}
