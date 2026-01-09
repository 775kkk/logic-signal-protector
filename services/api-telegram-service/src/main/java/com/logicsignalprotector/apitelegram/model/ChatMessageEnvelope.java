package com.logicsignalprotector.apitelegram.model;

import jakarta.validation.constraints.NotBlank;

/**
 * Universal chat message envelope (step 1.4).
 *
 * <p>This DTO allows different external chat adapters (Telegram, Discord, WebChat...) to pass raw
 * user messages into a single "brain" service without duplicating parsing logic.
 */
public record ChatMessageEnvelope(
    @NotBlank String channel,
    @NotBlank String externalUserId,
    @NotBlank String chatId,
    String messageId,
    String text,
    String callbackData) {}
