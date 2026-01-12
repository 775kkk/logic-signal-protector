package com.logicsignalprotector.apitelegram.model.v2;

import java.util.List;

public record SectionsBlock(List<Section> sections) implements ResponseBlock {
  @Override
  public BlockType type() {
    return BlockType.SECTIONS;
  }
}
