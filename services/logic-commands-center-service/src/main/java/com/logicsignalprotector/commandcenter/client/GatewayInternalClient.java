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
}
