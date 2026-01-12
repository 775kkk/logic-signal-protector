package com.logicsignalprotector.apigateway.internal.service;

import com.logicsignalprotector.apigateway.auth.domain.RoleEntity;
import com.logicsignalprotector.apigateway.auth.repository.RoleRepository;
import com.logicsignalprotector.apigateway.auth.repository.UserRepository;
import com.logicsignalprotector.apigateway.auth.service.AuthAuditService;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Step 1.5: bootstrap DEVONLYADMIN role by env on startup. */
@Component
@RequiredArgsConstructor
@Slf4j
public class DevConsoleBootstrapper implements ApplicationRunner {

  private static final String DEVONLYADMIN_ROLE = "DEVONLYADMIN";

  private final UserRepository users;
  private final RoleRepository roles;
  private final AuthAuditService audit;

  @Value("${dev.console.enabled:false}")
  private boolean enabled;

  @Value("${dev.console.user-ids:}")
  private String userIds;

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (!enabled) {
      log.info("Dev console is disabled; skipping DEVONLYADMIN bootstrap");
      return;
    }

    List<Long> ids = parseIds(userIds);
    if (ids.isEmpty()) {
      log.warn("Dev console is enabled but DEV_CONSOLE_USER_IDS is empty");
      return;
    }

    RoleEntity role =
        roles
            .findByCode(DEVONLYADMIN_ROLE)
            .orElseThrow(
                () -> new IllegalStateException("Role " + DEVONLYADMIN_ROLE + " not found"));

    for (Long id : ids) {
      var userOpt = users.findById(id);
      if (userOpt.isEmpty()) {
        log.warn("Dev console bootstrap: userId={} not found", id);
        continue;
      }
      var user = userOpt.get();
      boolean added = user.getRoles().add(role);
      users.save(user);

      if (added) {
        audit.log(user, "DEV_CONSOLE_BOOTSTRAP", "internal", "startup", "{\"userId\":" + id + "}");
      }
    }
  }

  private static List<Long> parseIds(String raw) {
    List<Long> out = new ArrayList<>();
    if (raw == null || raw.isBlank()) {
      return out;
    }
    String[] parts = raw.split(",");
    for (String p : parts) {
      String t = p == null ? "" : p.trim();
      if (t.isBlank()) continue;
      try {
        out.add(Long.parseLong(t));
      } catch (NumberFormatException e) {
        // skip invalid values
      }
    }
    return out;
  }
}
