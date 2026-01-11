package com.logicsignalprotector.apitelegram.model.v2;

public record TextBlock(String text) implements ResponseBlock {
  @Override
  public BlockType type() {
    return BlockType.TEXT;
  }
}
