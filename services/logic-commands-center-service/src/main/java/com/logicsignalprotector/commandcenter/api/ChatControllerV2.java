package com.logicsignalprotector.commandcenter.api;

import com.logicsignalprotector.commandcenter.api.dto.ChatMessageEnvelope;
import com.logicsignalprotector.commandcenter.api.dto.v2.ChatResponseV2;
import com.logicsignalprotector.commandcenter.domain.v2.ChatCommandHandlerV2;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/chat")
public class ChatControllerV2 {

  private final ChatCommandHandlerV2 handler;

  public ChatControllerV2(ChatCommandHandlerV2 handler) {
    this.handler = handler;
  }

  @PostMapping("/message/v2")
  public ChatResponseV2 message(@Valid @RequestBody ChatMessageEnvelope envelope) {
    return handler.handle(envelope);
  }
}
