package com.logicsignalprotector.apitelegram.model;

import java.util.List;

public record ChatResponse(List<OutgoingMessage> messages) {
  public static ChatResponse ofText(String text) {
    return new ChatResponse(List.of(OutgoingMessage.plain(text)));
  }
}
