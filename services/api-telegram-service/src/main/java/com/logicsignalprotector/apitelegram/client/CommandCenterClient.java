package com.logicsignalprotector.apitelegram.client;

import com.logicsignalprotector.apitelegram.model.ChatMessageEnvelope;
import com.logicsignalprotector.apitelegram.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class CommandCenterClient {

  private final RestClient rest;

  public CommandCenterClient(
      RestClient.Builder builder, @Value("${logic.commands-center.base-url}") String baseUrl) {
    this.rest = builder.baseUrl(baseUrl).build();
  }

  public ChatResponse send(ChatMessageEnvelope envelope) {
    ChatResponse res =
        rest.post()
            .uri("/internal/chat/message")
            .body(envelope)
            .retrieve()
            .body(ChatResponse.class);
    return res == null ? ChatResponse.ofText("No response from command-center") : res;
  }
}
