package com.logicsignalprotector.apitelegram.model.v2;

public record NoticeBlock(String text) implements ResponseBlock {
  @Override
  public BlockType type() {
    return BlockType.NOTICE;
  }
}
