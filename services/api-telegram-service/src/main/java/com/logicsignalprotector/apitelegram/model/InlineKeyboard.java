package com.logicsignalprotector.apitelegram.model;

import java.util.List;

public record InlineKeyboard(List<List<Button>> rows) {
  public record Button(String text, String callbackData) {}
}
