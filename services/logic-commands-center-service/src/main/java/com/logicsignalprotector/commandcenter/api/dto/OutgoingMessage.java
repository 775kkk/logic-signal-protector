package com.logicsignalprotector.commandcenter.api.dto;

import jakarta.validation.constraints.NotBlank;

public record OutgoingMessage(@NotBlank String text, UiHints uiHints) {

  public OutgoingMessage(String text) {
    this(text, null);
  }

  public static OutgoingMessage plain(String text) {
    return new OutgoingMessage(text, null);
  }

  public static OutgoingMessage pre(String text) {
    return new OutgoingMessage(text, UiHints.preformatted());
  }

  public OutgoingMessage preferEdit() {
    UiHints hints = uiHints == null ? new UiHints(true, false, null, null, null) : uiHints;
    return new OutgoingMessage(text, hints.withPreferEdit(true));
  }

  public OutgoingMessage deleteSourceMessage() {
    UiHints hints = uiHints == null ? new UiHints(false, true, null, null, null) : uiHints;
    return new OutgoingMessage(text, hints.withDeleteSourceMessage(true));
  }

  public OutgoingMessage withKeyboard(InlineKeyboard keyboard) {
    UiHints hints = uiHints == null ? new UiHints(false, false, null, null, keyboard) : uiHints;
    return new OutgoingMessage(text, hints.withInlineKeyboard(keyboard));
  }
}
