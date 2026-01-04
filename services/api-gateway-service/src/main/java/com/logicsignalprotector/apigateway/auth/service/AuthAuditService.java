package com.logicsignalprotector.apigateway.auth.service;

import com.logicsignalprotector.apigateway.auth.domain.AuthEventEntity;
import com.logicsignalprotector.apigateway.auth.domain.UserEntity;
import com.logicsignalprotector.apigateway.auth.repository.AuthEventRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthAuditService {

  private final AuthEventRepository authEventRepository;

  @Transactional
  public void log(UserEntity user, String type, String ip, String userAgent, String detailsJson) {
    AuthEventEntity event =
        new AuthEventEntity(user, type, Instant.now(), ip, userAgent, detailsJson);
    authEventRepository.save(event);
  }

  @Transactional
  public void logAnonymous(String type, String ip, String userAgent, String detailsJson) {
    AuthEventEntity event =
        new AuthEventEntity(null, type, Instant.now(), ip, userAgent, detailsJson);
    authEventRepository.save(event);
  }
}
