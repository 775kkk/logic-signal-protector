package com.logicsignalprotector.apigateway.internal.api.dto;

import jakarta.validation.constraints.NotBlank;

/** Step 1.3: internal API DTOs used by adapters and command-center. */
public final class InternalDtos {
  private InternalDtos() {}

  public record ResolveRequest(@NotBlank String providerCode, @NotBlank String externalUserId) {}

  public record ResolveResponse(
      boolean linked,
      Long userId,
      String login,
      java.util.List<String> roles,
      java.util.List<String> perms) {}

  public record CredentialsLinkRequest(
      @NotBlank String providerCode,
      @NotBlank String externalUserId,
      @NotBlank String login,
      @NotBlank String password) {}

  public record IssueAccessRequest(
      @NotBlank String providerCode, @NotBlank String externalUserId) {}

  public record TokensResponse(
      String accessToken,
      String tokenType,
      long expiresInSeconds,
      Long userId,
      String login,
      java.util.List<String> roles,
      java.util.List<String> perms) {}
}
