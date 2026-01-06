package com.logicsignalprotector.commandcenter.api;

import com.logicsignalprotector.commandcenter.api.dto.ChatMessageEnvelope;
import com.logicsignalprotector.commandcenter.api.dto.ChatResponse;
import com.logicsignalprotector.commandcenter.domain.ChatCommandHandler;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/chat")
public class ChatController {

  private final ChatCommandHandler handler;

  public ChatController(ChatCommandHandler handler) {
    this.handler = handler;
  }

  @PostMapping("/message")
  public ChatResponse message(@Valid @RequestBody ChatMessageEnvelope envelope) {
    return handler.handle(envelope);
  }
}
