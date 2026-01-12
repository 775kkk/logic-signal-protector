package com.logicsignalprotector.apitelegram.model.v2;

public record ErrorBlock(String code, String message, String hint, String details)
    implements ResponseBlock {
  @Override
  public BlockType type() {
    return BlockType.ERROR;
  }
}
