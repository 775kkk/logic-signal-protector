package com.logicsignalprotector.commandcenter.api.dto;

import java.util.List;

public record ChatResponse(List<OutgoingMessage> messages) {
  public static ChatResponse ofText(String text) {
    return new ChatResponse(List.of(new OutgoingMessage(text)));
  }
}
