package com.logicsignalprotector.apigateway.internal.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

/** Step 1.4: internal RBAC admin DTOs. */
public final class InternalRbacDtos {
  private InternalRbacDtos() {}

  public record ElevateByCodeRequest(
      @NotBlank String providerCode, @NotBlank String externalUserId, @NotBlank String code) {}

  public record ElevateByCodeResponse(
      boolean ok, String login, List<String> roles, List<String> perms) {}

  public record ActorLoginRequest(@NotNull Long actorUserId, @NotBlank String login) {}

  public record ActorRequest(@NotNull Long actorUserId) {}

  public record ActorTargetRoleRequest(
      @NotNull Long actorUserId, @NotBlank String targetLogin, @NotBlank String roleCode) {}

  public record ActorTargetPermRequest(
      @NotNull Long actorUserId,
      @NotBlank String targetLogin,
      @NotBlank String permCode,
      String reason,
      Instant expiresAt) {}

  public record OverrideDto(String permCode, boolean allowed, Instant expiresAt, String reason) {}

  public record UserInfoResponse(
      Long userId,
      String login,
      List<String> roles,
      List<String> perms,
      List<OverrideDto> overrides) {}

  public record ShortUserDto(Long userId, String login) {}

  public record UsersListResponse(List<ShortUserDto> users) {}

  public record RoleDto(String code, String name) {}

  public record RolesResponse(List<RoleDto> roles) {}

  public record PermDto(String code, String name) {}

  public record PermsResponse(List<PermDto> perms) {}
}
