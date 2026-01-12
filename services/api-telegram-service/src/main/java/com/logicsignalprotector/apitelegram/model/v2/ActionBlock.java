package com.logicsignalprotector.apitelegram.model.v2;

import java.util.List;

public record ActionBlock(List<ActionItem> actions) implements ResponseBlock {
  @Override
  public BlockType type() {
    return BlockType.ACTIONS;
  }
}
