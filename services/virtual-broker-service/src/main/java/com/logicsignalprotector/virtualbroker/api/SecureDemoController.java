package com.logicsignalprotector.virtualbroker.api;

import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/broker")
public class SecureDemoController {

  @GetMapping("/secure-sample")
  @PreAuthorize("hasAuthority('PERM_BROKER_READ')")
  public Map<String, Object> secureSample(@AuthenticationPrincipal Jwt jwt) {
    return Map.of(
        "status", "ok",
        "subject", jwt.getSubject(),
        "uid", jwt.getClaim("uid"),
        "perms", jwt.getClaim("perms"));
  }

  @PostMapping("/trade-sample")
  @PreAuthorize("hasAuthority('PERM_BROKER_TRADE')")
  public Map<String, Object> tradeSample(@AuthenticationPrincipal Jwt jwt) {
    return Map.of(
        "status", "accepted",
        "user", jwt.getSubject(),
        "note", "stub trade endpoint (step 1.3)");
  }
}
