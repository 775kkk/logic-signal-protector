package com.logicsignalprotector.apigateway.internal.service;

import com.logicsignalprotector.apigateway.auth.domain.RoleEntity;
import com.logicsignalprotector.apigateway.auth.domain.UserEntity;
import com.logicsignalprotector.apigateway.auth.repository.ExternalAccountRepository;
import com.logicsignalprotector.apigateway.auth.repository.RoleRepository;
import com.logicsignalprotector.apigateway.auth.repository.UserRepository;
import com.logicsignalprotector.apigateway.auth.service.AuthAuditService;
import com.logicsignalprotector.apigateway.auth.service.PermissionService;
import com.logicsignalprotector.apigateway.common.web.ForbiddenException;
import com.logicsignalprotector.apigateway.common.web.NotFoundException;
import com.logicsignalprotector.apigateway.internal.api.dto.InternalRbacDtos;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RbacAdminService {

  private final UserRepository users;
  private final RoleRepository roles;
  private final ExternalAccountRepository externalAccounts;
  private final JdbcTemplate jdbc;
  private final PermissionService permissionService;
  private final AuthAuditService audit;

  @Value("${dev.admin-code.enabled:false}")
  private boolean devAdminCodeEnabled;

  @Value("${dev.admin-code.value:}")
  private String devAdminCodeValue;

  private static final String ADMIN_ROLE = "ADMIN";
  private static final String MANAGE_PERM = "ADMIN_USERS_PERMS_REVOKE";

  @Transactional
  public InternalRbacDtos.ElevateByCodeResponse elevateByCode(
      String providerCode, String externalUserId, String code) {

    if (!devAdminCodeEnabled) {
      audit.logAnonymous("ADMIN_CODE_FAIL", "internal", "internal", "{\"reason\":\"disabled\"}");
      throw new ForbiddenException("DEV_ADMIN_CODE is disabled");
    }

    String expected = devAdminCodeValue == null ? "" : devAdminCodeValue.trim();
    if (expected.isBlank() || code == null || !expected.equals(code.trim())) {
      audit.logAnonymous("ADMIN_CODE_FAIL", "internal", "internal", "{\"reason\":\"bad_code\"}");
      throw new ForbiddenException("Invalid admin code");
    }

    var extOpt = externalAccounts.findByProviderCodeAndExternalId(providerCode, externalUserId);
    if (extOpt.isEmpty()) {
      audit.logAnonymous("ADMIN_CODE_FAIL", "internal", "internal", "{\"reason\":\"not_linked\"}");
      throw new NotFoundException("External account is not linked");
    }

    UserEntity user = extOpt.get().getUser();
    RoleEntity admin =
        roles
            .findByCode(ADMIN_ROLE)
            .orElseThrow(() -> new IllegalStateException("Role ADMIN not found"));

    boolean added = user.getRoles().add(admin);
    users.save(user);

    audit.log(
        user, added ? "ADMIN_CODE_SUCCESS" : "ADMIN_CODE_SUCCESS", "internal", "internal", null);

    return new InternalRbacDtos.ElevateByCodeResponse(
        true,
        user.getLogin(),
        user.getRoles().stream().map(RoleEntity::getCode).sorted().collect(Collectors.toList()),
        permissionService.getEffectivePermissionCodes(user.getId()).stream()
            .sorted()
            .collect(Collectors.toList()));
  }

  @Transactional(readOnly = true)
  public InternalRbacDtos.UserInfoResponse getUser(Long actorUserId, String login) {
    requireManage(actorUserId);
    UserEntity user =
        users.findByLogin(login).orElseThrow(() -> new NotFoundException("User not found"));

    return toUserInfo(user);
  }

  @Transactional(readOnly = true)
  public InternalRbacDtos.UsersListResponse listUsers(Long actorUserId) {
    requireManage(actorUserId);
    List<InternalRbacDtos.ShortUserDto> out =
        users.findAll(org.springframework.data.domain.Sort.by("login")).stream()
            .map(u -> new InternalRbacDtos.ShortUserDto(u.getId(), u.getLogin()))
            .collect(Collectors.toList());
    return new InternalRbacDtos.UsersListResponse(out);
  }

  @Transactional(readOnly = true)
  public InternalRbacDtos.RolesResponse listRoles(Long actorUserId) {
    requireManage(actorUserId);
    List<InternalRbacDtos.RoleDto> out =
        roles.findAll(org.springframework.data.domain.Sort.by("code")).stream()
            .map(r -> new InternalRbacDtos.RoleDto(r.getCode(), r.getName()))
            .collect(Collectors.toList());
    return new InternalRbacDtos.RolesResponse(out);
  }

  @Transactional(readOnly = true)
  public InternalRbacDtos.PermsResponse listPerms(Long actorUserId) {
    requireManage(actorUserId);
    String sql = "SELECT code, name FROM permissions ORDER BY code";
    List<InternalRbacDtos.PermDto> out =
        jdbc.query(
            sql, (rs, rowNum) -> new InternalRbacDtos.PermDto(rs.getString(1), rs.getString(2)));
    return new InternalRbacDtos.PermsResponse(out);
  }

  @Transactional
  public InternalRbacDtos.UserInfoResponse grantRole(
      Long actorUserId, String targetLogin, String roleCode) {
    requireManage(actorUserId);
    UserEntity user =
        users
            .findByLogin(targetLogin)
            .orElseThrow(() -> new NotFoundException("Target user not found"));
    RoleEntity role =
        roles.findByCode(roleCode).orElseThrow(() -> new NotFoundException("Role not found"));

    user.getRoles().add(role);
    users.save(user);

    audit.log(
        null,
        "RBAC_GRANT_ROLE",
        "internal",
        "internal",
        "{\"actorUserId\":"
            + actorUserId
            + ",\"targetLogin\":\""
            + escape(targetLogin)
            + "\",\"roleCode\":\""
            + escape(roleCode)
            + "\"}");

    return toUserInfo(user);
  }

  @Transactional
  public InternalRbacDtos.UserInfoResponse revokeRole(
      Long actorUserId, String targetLogin, String roleCode) {
    requireManage(actorUserId);
    UserEntity user =
        users
            .findByLogin(targetLogin)
            .orElseThrow(() -> new NotFoundException("Target user not found"));
    RoleEntity role =
        roles.findByCode(roleCode).orElseThrow(() -> new NotFoundException("Role not found"));

    user.getRoles().remove(role);
    users.save(user);

    audit.log(
        null,
        "RBAC_REVOKE_ROLE",
        "internal",
        "internal",
        "{\"actorUserId\":"
            + actorUserId
            + ",\"targetLogin\":\""
            + escape(targetLogin)
            + "\",\"roleCode\":\""
            + escape(roleCode)
            + "\"}");

    return toUserInfo(user);
  }

  @Transactional
  public InternalRbacDtos.UserInfoResponse grantPerm(
      Long actorUserId,
      String targetLogin,
      String permCode,
      String reason,
      java.time.Instant expiresAt) {
    return upsertOverride(actorUserId, targetLogin, permCode, true, reason, expiresAt);
  }

  @Transactional
  public InternalRbacDtos.UserInfoResponse denyPerm(
      Long actorUserId,
      String targetLogin,
      String permCode,
      String reason,
      java.time.Instant expiresAt) {
    return upsertOverride(actorUserId, targetLogin, permCode, false, reason, expiresAt);
  }

  @Transactional
  public InternalRbacDtos.UserInfoResponse revokePerm(
      Long actorUserId, String targetLogin, String permCode) {
    requireManage(actorUserId);
    UserEntity user =
        users
            .findByLogin(targetLogin)
            .orElseThrow(() -> new NotFoundException("Target user not found"));
    Long permId = findPermissionIdOrThrow(permCode);

    jdbc.update(
        "DELETE FROM user_permission_overrides WHERE user_id = ? AND permission_id = ?",
        user.getId(),
        permId);

    audit.log(
        null,
        "RBAC_REVOKE_PERM",
        "internal",
        "internal",
        "{\"actorUserId\":"
            + actorUserId
            + ",\"targetLogin\":\""
            + escape(targetLogin)
            + "\",\"permCode\":\""
            + escape(permCode)
            + "\"}");

    return toUserInfo(user);
  }

  private InternalRbacDtos.UserInfoResponse upsertOverride(
      Long actorUserId,
      String targetLogin,
      String permCode,
      boolean allowed,
      String reason,
      java.time.Instant expiresAt) {

    requireManage(actorUserId);
    UserEntity user =
        users
            .findByLogin(targetLogin)
            .orElseThrow(() -> new NotFoundException("Target user not found"));
    Long permId = findPermissionIdOrThrow(permCode);

    String sql =
        "INSERT INTO user_permission_overrides(user_id, permission_id, is_allowed, expires_at, reason) "
            + "VALUES(?,?,?,?,?) "
            + "ON CONFLICT(user_id, permission_id) DO UPDATE SET "
            + "  is_allowed = EXCLUDED.is_allowed, "
            + "  expires_at = EXCLUDED.expires_at, "
            + "  reason = EXCLUDED.reason, "
            + "  created_at = NOW()";

    jdbc.update(sql, user.getId(), permId, allowed, expiresAt, trimOrNull(reason));

    audit.log(
        null,
        allowed ? "RBAC_GRANT_PERM" : "RBAC_DENY_PERM",
        "internal",
        "internal",
        "{\"actorUserId\":"
            + actorUserId
            + ",\"targetLogin\":\""
            + escape(targetLogin)
            + "\",\"permCode\":\""
            + escape(permCode)
            + "\",\"allowed\":"
            + allowed
            + "}");

    return toUserInfo(user);
  }

  private Long findPermissionIdOrThrow(String permCode) {
    if (permCode == null || permCode.isBlank()) {
      throw new IllegalArgumentException("permCode is required");
    }
    List<Long> ids =
        jdbc.query(
            "SELECT id FROM permissions WHERE code = ?", (rs, rowNum) -> rs.getLong(1), permCode);
    if (ids.isEmpty()) {
      throw new NotFoundException("Permission not found");
    }
    return ids.get(0);
  }

  private void requireManage(Long actorUserId) {
    if (actorUserId == null) {
      throw new IllegalArgumentException("actorUserId is required");
    }
    Set<String> perms = permissionService.getEffectivePermissionCodes(actorUserId);
    if (!perms.contains(MANAGE_PERM)) {
      throw new ForbiddenException("Missing permission " + MANAGE_PERM);
    }
  }

  private InternalRbacDtos.UserInfoResponse toUserInfo(UserEntity user) {
    Long userId = user.getId();

    List<String> roleCodes =
        user.getRoles().stream().map(RoleEntity::getCode).sorted().collect(Collectors.toList());

    List<String> effectivePerms =
        permissionService.getEffectivePermissionCodes(userId).stream()
            .sorted()
            .collect(Collectors.toList());

    List<InternalRbacDtos.OverrideDto> overrides =
        jdbc.query(
            "SELECT p.code, uo.is_allowed, uo.expires_at, uo.reason "
                + "FROM user_permission_overrides uo "
                + "JOIN permissions p ON p.id = uo.permission_id "
                + "WHERE uo.user_id = ? "
                + "ORDER BY p.code",
            (rs, rowNum) ->
                new InternalRbacDtos.OverrideDto(
                    rs.getString(1),
                    rs.getBoolean(2),
                    (java.time.Instant) rs.getObject(3, java.time.Instant.class),
                    rs.getString(4)),
            userId);

    return new InternalRbacDtos.UserInfoResponse(
        userId, user.getLogin(), roleCodes, effectivePerms, overrides);
  }

  private static String trimOrNull(String s) {
    if (s == null) return null;
    String t = s.trim();
    return t.isBlank() ? null : t.substring(0, Math.min(t.length(), 256));
  }

  private static String escape(String s) {
    if (s == null) return "";
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
