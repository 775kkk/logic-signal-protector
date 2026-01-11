package com.logicsignalprotector.commandcenter.api.dto.v2;

import java.util.List;

public record TableBlock(List<String> columns, List<List<String>> rows, String format)
    implements ResponseBlock {
  @Override
  public BlockType type() {
    return BlockType.TABLE;
  }
}
