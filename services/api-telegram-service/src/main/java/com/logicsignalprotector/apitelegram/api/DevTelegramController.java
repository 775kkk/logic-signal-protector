package com.logicsignalprotector.apitelegram.api;

import com.logicsignalprotector.apitelegram.client.CommandCenterClient;
import com.logicsignalprotector.apitelegram.model.ChatMessageEnvelope;
import com.logicsignalprotector.apitelegram.model.ChatResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
    ChatMessageEnvelope env =
        new ChatMessageEnvelope(
            "telegram",
            String.valueOf(req.telegramUserId()),
            String.valueOf(req.chatId()),
            null,
            req.text(),
            null);
    return commandCenter.send(env);
  }
}
