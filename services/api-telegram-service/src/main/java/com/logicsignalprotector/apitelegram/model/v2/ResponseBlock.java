package com.logicsignalprotector.apitelegram.model.v2;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = TextBlock.class, name = "TEXT"),
  @JsonSubTypes.Type(value = NoticeBlock.class, name = "NOTICE"),
  @JsonSubTypes.Type(value = TableBlock.class, name = "TABLE"),
  @JsonSubTypes.Type(value = ListBlock.class, name = "LIST"),
  @JsonSubTypes.Type(value = SectionsBlock.class, name = "SECTIONS"),
  @JsonSubTypes.Type(value = ErrorBlock.class, name = "ERROR"),
  @JsonSubTypes.Type(value = ActionBlock.class, name = "ACTIONS")
})
public interface ResponseBlock {
  BlockType type();
}
