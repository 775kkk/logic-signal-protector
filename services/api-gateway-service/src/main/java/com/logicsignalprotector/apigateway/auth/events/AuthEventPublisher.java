package com.logicsignalprotector.apigateway.auth.events;

import com.logicsignalprotector.apigateway.auth.domain.AuthEventEntity;

/**
 * Step 1.3: abstraction for publishing auth events outside the gateway.
 *
 * <p>Today: No-op implementation. Future: Kafka/Rabbit/etc without rewriting AuthAuditService.
 */
public interface AuthEventPublisher {
  void publish(AuthEventEntity event);
}
