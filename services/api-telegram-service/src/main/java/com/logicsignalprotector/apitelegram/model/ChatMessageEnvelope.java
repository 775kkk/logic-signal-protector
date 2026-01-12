package com.logicsignalprotector.apitelegram.model;

import jakarta.validation.constraints.NotBlank;

/**
 * Универсальный конверт для сообщений в чате (шаг 1.4).
 *
 * <p>позволяет различным внешним адаптерам чата (Telegram, Discord, WebChat...) передавать
 * необработанные передача пользовательских сообщений в единый "мозговой" сервис без дублирования
 * логики синтаксического анализа.
 */
public record ChatMessageEnvelope(
    @NotBlank String channel,
    @NotBlank String externalUserId,
    @NotBlank String chatId,
    String messageId,
    String text,
    String callbackData,
    String correlationId,
    String sessionId,
    String locale) {}
