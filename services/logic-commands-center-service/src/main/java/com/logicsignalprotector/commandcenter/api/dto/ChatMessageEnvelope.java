package com.logicsignalprotector.commandcenter.api.dto;

import jakarta.validation.constraints.NotBlank;

public record ChatMessageEnvelope(
    @NotBlank String channel,
    @NotBlank String externalUserId,
    @NotBlank String chatId,
    String messageId,
    @NotBlank String text) {}
