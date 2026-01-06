package com.logicsignalprotector.apigateway.auth.service;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Step 1.3 (stub): calculates effective permissions for a user.
 *
 * <p>Model: - role-based permissions (user_roles -> role_permissions -> permissions) - per-user
 * overrides (user_permission_overrides)
 *
 * <p>Rules: - if override exists and is_allowed=false -> permission is denied (DENY wins) - if
 * override exists and is_allowed=true -> permission is granted - expired overrides are ignored
 */
@Service
@RequiredArgsConstructor
public class PermissionService {

  private final JdbcTemplate jdbc;

  public Set<String> getEffectivePermissionCodes(long userId) {
    Set<String> effective = new HashSet<>(loadRolePermissions(userId));

    Map<String, Boolean> overrides = loadActiveOverrides(userId);
    for (Map.Entry<String, Boolean> e : overrides.entrySet()) {
      String code = e.getKey();
      boolean allowed = e.getValue();
      if (allowed) {
        effective.add(code);
      } else {
        effective.remove(code);
      }
    }

    return Collections.unmodifiableSet(effective);
  }

  private List<String> loadRolePermissions(long userId) {
    String sql =
        "SELECT DISTINCT p.code "
            + "FROM permissions p "
            + "JOIN role_permissions rp ON rp.permission_id = p.id "
            + "JOIN user_roles ur ON ur.role_id = rp.role_id "
            + "WHERE ur.user_id = ?";

    return jdbc.query(sql, (rs, rowNum) -> rs.getString(1), userId);
  }

  private Map<String, Boolean> loadActiveOverrides(long userId) {
    String sql =
        "SELECT p.code, uo.is_allowed "
            + "FROM user_permission_overrides uo "
            + "JOIN permissions p ON p.id = uo.permission_id "
            + "WHERE uo.user_id = ? "
            + "  AND (uo.expires_at IS NULL OR uo.expires_at > NOW())";

    Map<String, Boolean> out = new HashMap<>();
    jdbc.query(
        sql,
        rs -> {
          out.put(rs.getString(1), rs.getBoolean(2));
        },
        userId);

    return out;
  }
}
