package com.logicsignalprotector.commandcenter.api.dto;

import java.util.List;

public record InlineKeyboard(List<List<Button>> rows) {
  public record Button(String text, String callbackData) {}
}
