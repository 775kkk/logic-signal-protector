package com.logicsignalprotector.apigateway.internal.service;

import com.logicsignalprotector.apigateway.auth.domain.UserEntity;
import com.logicsignalprotector.apigateway.auth.repository.UserRepository;
import com.logicsignalprotector.apigateway.auth.service.AuthAuditService;
import com.logicsignalprotector.apigateway.auth.service.PermissionService;
import com.logicsignalprotector.apigateway.common.web.ForbiddenException;
import com.logicsignalprotector.apigateway.common.web.NotFoundException;
import com.logicsignalprotector.apigateway.internal.api.dto.InternalUsersDtos;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserHardDeleteService {

  private static final String PERM_DEVGOD = "DEVGOD";
  private static final String PERM_USERS_HARD_DELETE = "USERS_HARD_DELETE";

  private final UserRepository users;
  private final PermissionService permissions;
  private final AuthAuditService audit;

  @Transactional
  public InternalUsersDtos.HardDeleteResponse hardDelete(
      Long actorUserId, Long targetUserId, String targetLogin) {
    requireHardDeletePerm(actorUserId);

    if (targetUserId == null && (targetLogin == null || targetLogin.isBlank())) {
      throw new IllegalArgumentException("targetUserId or targetLogin is required");
    }

    UserEntity target = targetUserId != null ? findById(targetUserId) : findByLogin(targetLogin);

    if (actorUserId.equals(target.getId())) {
      throw new ForbiddenException("Cannot hard delete self");
    }

    users.delete(target);

    audit.log(
        null,
        "USERS_HARD_DELETE",
        "internal",
        "internal",
        "{\"actorUserId\":"
            + actorUserId
            + ",\"targetUserId\":"
            + target.getId()
            + ",\"targetLogin\":\""
            + escape(target.getLogin())
            + "\"}");

    return new InternalUsersDtos.HardDeleteResponse(true, target.getId(), target.getLogin());
  }

  private void requireHardDeletePerm(Long actorUserId) {
    if (actorUserId == null) {
      throw new IllegalArgumentException("actorUserId is required");
    }
    Set<String> raw = permissions.getRawPermissionCodes(actorUserId);
    if (!raw.contains(PERM_DEVGOD) || !raw.contains(PERM_USERS_HARD_DELETE)) {
      throw new ForbiddenException("Missing permissions for hard delete");
    }
  }

  private UserEntity findById(Long id) {
    return users.findById(id).orElseThrow(() -> new NotFoundException("User not found"));
  }

  private UserEntity findByLogin(String login) {
    String trimmed = login == null ? "" : login.trim();
    if (trimmed.isBlank()) {
      throw new IllegalArgumentException("targetLogin is required");
    }
    return users.findByLogin(trimmed).orElseThrow(() -> new NotFoundException("User not found"));
  }

  private static String escape(String s) {
    if (s == null) return "";
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
