package com.logicsignalprotector.commandcenter.api.dto.v2;

import java.util.List;

public record ListBlock(List<String> items) implements ResponseBlock {
  @Override
  public BlockType type() {
    return BlockType.LIST;
  }
}
