package com.logicsignalprotector.apigateway.auth.events;

import com.logicsignalprotector.apigateway.auth.domain.AuthEventEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Step 1.3: default implementation - does nothing.
 *
 * <p>Keep it here so later we can replace this bean with a Kafka publisher.
 */
@Component
@Slf4j
public class NoopAuthEventPublisher implements AuthEventPublisher {

  @Override
  public void publish(AuthEventEntity event) {
    // Intentionally no-op. Uncomment if you want to observe events in logs.
    // log.debug("Auth event: type={}, userId={}, at={}", event.getType(),
    //     event.getUser() == null ? null : event.getUser().getId(), event.getCreatedAt());
  }
}
