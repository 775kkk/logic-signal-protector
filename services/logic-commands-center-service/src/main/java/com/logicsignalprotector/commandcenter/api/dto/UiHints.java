package com.logicsignalprotector.commandcenter.api.dto;

public record UiHints(
    boolean preferEdit,
    boolean deleteSourceMessage,
    RenderMode renderMode,
    String parseModeHint,
    InlineKeyboard inlineKeyboard) {

  public static UiHints preformatted() {
    return new UiHints(false, false, RenderMode.PRE, "HTML", null);
  }

  public UiHints withPreferEdit(boolean value) {
    return new UiHints(value, deleteSourceMessage, renderMode, parseModeHint, inlineKeyboard);
  }

  public UiHints withDeleteSourceMessage(boolean value) {
    return new UiHints(preferEdit, value, renderMode, parseModeHint, inlineKeyboard);
  }

  public UiHints withInlineKeyboard(InlineKeyboard keyboard) {
    return new UiHints(preferEdit, deleteSourceMessage, renderMode, parseModeHint, keyboard);
  }
}
