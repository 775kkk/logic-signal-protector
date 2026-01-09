package com.logicsignalprotector.commandcenter.client;

import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
@Slf4j
public class GatewayInternalClient {

  private final RestClient rest;
  private final String internalToken;

  public GatewayInternalClient(
      RestClient.Builder builder,
      @Value("${gateway.internal.base-url}") String baseUrl,
      @Value("${gateway.internal.token}") String internalToken) {
    this.rest = builder.baseUrl(baseUrl).build();
    this.internalToken = internalToken == null ? "" : internalToken.trim();
  }

  public ResolveResponse resolve(String providerCode, String externalUserId) {
    ResolveResponse res =
        rest.post()
            .uri("/internal/identity/resolve")
            .header("X-Internal-Token", internalToken)
            .body(new ResolveRequest(providerCode, externalUserId))
            .retrieve()
            .body(ResolveResponse.class);
    return res == null ? new ResolveResponse(false, null, null, List.of(), List.of()) : res;
  }

  public TokensResponse registerAndLink(
      String providerCode, String externalUserId, String login, String password) {
    TokensResponse res =
        rest.post()
            .uri("/internal/auth/register-and-link")
            .header("X-Internal-Token", internalToken)
            .body(new CredentialsLinkRequest(providerCode, externalUserId, login, password))
            .retrieve()
            .body(TokensResponse.class);
    return res;
  }

  public TokensResponse loginAndLink(
      String providerCode, String externalUserId, String login, String password) {
    TokensResponse res =
        rest.post()
            .uri("/internal/auth/login-and-link")
            .header("X-Internal-Token", internalToken)
            .body(new CredentialsLinkRequest(providerCode, externalUserId, login, password))
            .retrieve()
            .body(TokensResponse.class);
    return res;
  }

  public TokensResponse issueAccess(String providerCode, String externalUserId) {
    TokensResponse res =
        rest.post()
            .uri("/internal/auth/issue-access")
            .header("X-Internal-Token", internalToken)
            .body(Map.of("providerCode", providerCode, "externalUserId", externalUserId))
            .retrieve()
            .body(TokensResponse.class);
    return res;
  }

  // Step 1.4: unlink external account (logout semantics for Telegram).
  public OkResponse unlink(String providerCode, String externalUserId) {
    OkResponse res =
        rest.post()
            .uri("/internal/identity/unlink")
            .header("X-Internal-Token", internalToken)
            .body(new UnlinkRequest(providerCode, externalUserId))
            .retrieve()
            .body(OkResponse.class);
    return res == null ? new OkResponse(true) : res;
  }

  // Step 1.4: dev-only backdoor (shared code) to grant ADMIN role.
  public ElevateByCodeResponse elevateByCode(
      String providerCode, String externalUserId, String code) {
    ElevateByCodeResponse res =
        rest.post()
            .uri("/internal/rbac/elevate-by-code")
            .header("X-Internal-Token", internalToken)
            .body(new ElevateByCodeRequest(providerCode, externalUserId, code))
            .retrieve()
            .body(ElevateByCodeResponse.class);
    return res;
  }

  public UserInfoResponse getUser(long actorUserId, String login) {
    return rest.post()
        .uri("/internal/rbac/users/get")
        .header("X-Internal-Token", internalToken)
        .body(new ActorLoginRequest(actorUserId, login))
        .retrieve()
        .body(UserInfoResponse.class);
  }

  public UsersListResponse listUsers(long actorUserId) {
    return rest.post()
        .uri("/internal/rbac/users/list")
        .header("X-Internal-Token", internalToken)
        .body(new ActorRequest(actorUserId))
        .retrieve()
        .body(UsersListResponse.class);
  }

  public RolesResponse listRoles(long actorUserId) {
    return rest.post()
        .uri("/internal/rbac/roles/list")
        .header("X-Internal-Token", internalToken)
        .body(new ActorRequest(actorUserId))
        .retrieve()
        .body(RolesResponse.class);
  }

  public PermsResponse listPerms(long actorUserId) {
    return rest.post()
        .uri("/internal/rbac/perms/list")
        .header("X-Internal-Token", internalToken)
        .body(new ActorRequest(actorUserId))
        .retrieve()
        .body(PermsResponse.class);
  }

  public UserInfoResponse grantRole(long actorUserId, String targetLogin, String roleCode) {
    return rest.post()
        .uri("/internal/rbac/roles/grant")
        .header("X-Internal-Token", internalToken)
        .body(new ActorTargetRoleRequest(actorUserId, targetLogin, roleCode))
        .retrieve()
        .body(UserInfoResponse.class);
  }

  public UserInfoResponse revokeRole(long actorUserId, String targetLogin, String roleCode) {
    return rest.post()
        .uri("/internal/rbac/roles/revoke")
        .header("X-Internal-Token", internalToken)
        .body(new ActorTargetRoleRequest(actorUserId, targetLogin, roleCode))
        .retrieve()
        .body(UserInfoResponse.class);
  }

  public UserInfoResponse grantPerm(
      long actorUserId, String targetLogin, String permCode, String reason) {
    return rest.post()
        .uri("/internal/rbac/perms/grant")
        .header("X-Internal-Token", internalToken)
        .body(new ActorTargetPermRequest(actorUserId, targetLogin, permCode, reason, null))
        .retrieve()
        .body(UserInfoResponse.class);
  }

  public UserInfoResponse denyPerm(
      long actorUserId, String targetLogin, String permCode, String reason) {
    return rest.post()
        .uri("/internal/rbac/perms/deny")
        .header("X-Internal-Token", internalToken)
        .body(new ActorTargetPermRequest(actorUserId, targetLogin, permCode, reason, null))
        .retrieve()
        .body(UserInfoResponse.class);
  }

  public UserInfoResponse revokePerm(long actorUserId, String targetLogin, String permCode) {
    return rest.post()
        .uri("/internal/rbac/perms/revoke")
        .header("X-Internal-Token", internalToken)
        .body(new ActorTargetPermRequest(actorUserId, targetLogin, permCode, null, null))
        .retrieve()
        .body(UserInfoResponse.class);
  }

  // Step 1.5: command switches
  public ListCommandSwitchesResponse listCommandSwitches() {
    return rest.get()
        .uri("/internal/commands/list")
        .header("X-Internal-Token", internalToken)
        .retrieve()
        .body(ListCommandSwitchesResponse.class);
  }

  public SetEnabledResponse setCommandEnabled(
      long actorUserId, String commandCode, boolean enabled, String note) {
    return rest.post()
        .uri("/internal/commands/set-enabled")
        .header("X-Internal-Token", internalToken)
        .body(new SetEnabledRequest(actorUserId, commandCode, enabled, note))
        .retrieve()
        .body(SetEnabledResponse.class);
  }

  public HardDeleteResponse hardDeleteUser(
      long actorUserId, Long targetUserId, String targetLogin) {
    return rest.post()
        .uri("/internal/users/hard-delete")
        .header("X-Internal-Token", internalToken)
        .body(new HardDeleteRequest(actorUserId, targetUserId, targetLogin))
        .retrieve()
        .body(HardDeleteResponse.class);
  }

  public record ResolveRequest(String providerCode, String externalUserId) {}

  public record ResolveResponse(
      boolean linked, Long userId, String login, List<String> roles, List<String> perms) {}

  public record CredentialsLinkRequest(
      String providerCode, String externalUserId, String login, String password) {}

  public record TokensResponse(
      String accessToken,
      String tokenType,
      long expiresInSeconds,
      Long userId,
      String login,
      List<String> roles,
      List<String> perms) {}

  public record UnlinkRequest(String providerCode, String externalUserId) {}

  public record OkResponse(boolean ok) {}

  public record ElevateByCodeRequest(String providerCode, String externalUserId, String code) {}

  public record ElevateByCodeResponse(
      boolean ok, String login, List<String> roles, List<String> perms) {}

  public record ActorRequest(long actorUserId) {}

  public record ActorLoginRequest(long actorUserId, String login) {}

  public record ActorTargetRoleRequest(long actorUserId, String targetLogin, String roleCode) {}

  public record ActorTargetPermRequest(
      long actorUserId,
      String targetLogin,
      String permCode,
      String reason,
      java.time.Instant expiresAt) {}

  public record OverrideDto(
      String permCode, boolean allowed, java.time.Instant expiresAt, String reason) {}

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

  public record CommandSwitchDto(
      String commandCode,
      boolean enabled,
      java.time.Instant updatedAt,
      Long updatedByUserId,
      String note) {}

  public record ListCommandSwitchesResponse(List<CommandSwitchDto> switches) {}

  public record SetEnabledRequest(
      long actorUserId, String commandCode, boolean enabled, String note) {}

  public record SetEnabledResponse(CommandSwitchDto value) {}

  public record HardDeleteRequest(Long actorUserId, Long targetUserId, String targetLogin) {}

  public record HardDeleteResponse(boolean ok, Long deletedUserId, String deletedLogin) {}
}
