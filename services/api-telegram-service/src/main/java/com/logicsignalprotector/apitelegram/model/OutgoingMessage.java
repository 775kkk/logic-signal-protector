package com.logicsignalprotector.apitelegram.model;

import jakarta.validation.constraints.NotBlank;

public record OutgoingMessage(@NotBlank String text, UiHints uiHints) {

  public OutgoingMessage(String text) {
    this(text, null);
  }

  public static OutgoingMessage plain(String text) {
    return new OutgoingMessage(text, null);
  }
}
