package com.logicsignalprotector.apigateway.internal.api;

import com.logicsignalprotector.apigateway.auth.domain.UserEntity;
import com.logicsignalprotector.apigateway.auth.service.AuthAuditService;
import com.logicsignalprotector.apigateway.auth.service.ExternalAccountService;
import com.logicsignalprotector.apigateway.auth.service.PermissionService;
import com.logicsignalprotector.apigateway.auth.service.TokenService;
import com.logicsignalprotector.apigateway.auth.service.UserService;
import com.logicsignalprotector.apigateway.internal.api.dto.InternalDtos;
import jakarta.validation.Valid;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/auth")
public class InternalAuthController {

  private final UserService userService;
  private final TokenService tokenService;
  private final ExternalAccountService externalAccounts;
  private final PermissionService permissionService;
  private final AuthAuditService audit;

  public InternalAuthController(
      UserService userService,
      TokenService tokenService,
      ExternalAccountService externalAccounts,
      PermissionService permissionService,
      AuthAuditService audit) {
    this.userService = userService;
    this.tokenService = tokenService;
    this.externalAccounts = externalAccounts;
    this.permissionService = permissionService;
    this.audit = audit;
  }

  /** Step 1.3: register + link external account + issue access token. */
  @PostMapping("/register-and-link")
  @Transactional
  public ResponseEntity<InternalDtos.TokensResponse> registerAndLink(
      @Valid @RequestBody InternalDtos.CredentialsLinkRequest req) {
    UserEntity user = userService.register(req.login(), req.password());
    externalAccounts.link(user, req.providerCode(), req.externalUserId());
    audit.log(user, "REGISTER_INTERNAL", "internal", "internal", null);
    return ResponseEntity.status(HttpStatus.CREATED).body(issueAccessForUser(user));
  }

  /** Step 1.3: login + link external account + issue access token. */
  @PostMapping("/login-and-link")
  @Transactional
  public InternalDtos.TokensResponse loginAndLink(
      @Valid @RequestBody InternalDtos.CredentialsLinkRequest req) {
    UserEntity user = userService.authenticate(req.login(), req.password());
    externalAccounts.link(user, req.providerCode(), req.externalUserId());
    audit.log(user, "LOGIN_INTERNAL", "internal", "internal", null);
    return issueAccessForUser(user);
  }

  /** Step 1.3: issue access token by external account (provider, externalId). */
  @PostMapping("/issue-access")
  @Transactional(readOnly = true)
  public InternalDtos.TokensResponse issueAccess(
      @Valid @RequestBody InternalDtos.IssueAccessRequest req) {

    var opt = userService.findByExternalAccount(req.providerCode(), req.externalUserId());
    if (opt.isEmpty()) {
      // keep it simple: command-center will treat this as not-linked
      return new InternalDtos.TokensResponse(null, "Bearer", 0, null, null, List.of(), List.of());
    }
    return issueAccessForUser(opt.get());
  }

  private InternalDtos.TokensResponse issueAccessForUser(UserEntity user) {
    var access = tokenService.issueAccessToken(user);
    Long userId = user.getId();
    List<String> roles =
        user.getRoles().stream().map(r -> r.getCode()).sorted().collect(Collectors.toList());
    List<String> perms =
        permissionService.getEffectivePermissionCodes(userId).stream()
            .sorted()
            .collect(Collectors.toList());

    return new InternalDtos.TokensResponse(
        access.token(), "Bearer", access.ttl().toSeconds(), userId, user.getLogin(), roles, perms);
  }
}
