package com.logicsignalprotector.apitelegram.model;

public record UiHints(
    boolean preferEdit,
    boolean deleteSourceMessage,
    RenderMode renderMode,
    String parseModeHint,
    InlineKeyboard inlineKeyboard) {}
