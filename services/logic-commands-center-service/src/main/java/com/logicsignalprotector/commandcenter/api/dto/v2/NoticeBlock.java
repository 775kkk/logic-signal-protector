package com.logicsignalprotector.commandcenter.api.dto.v2;

public record NoticeBlock(String text) implements ResponseBlock {
  @Override
  public BlockType type() {
    return BlockType.NOTICE;
  }
}
