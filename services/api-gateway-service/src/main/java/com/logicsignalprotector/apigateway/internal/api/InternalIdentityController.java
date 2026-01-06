package com.logicsignalprotector.apigateway.internal.api;

import com.logicsignalprotector.apigateway.auth.repository.ExternalAccountRepository;
import com.logicsignalprotector.apigateway.auth.service.AuthAuditService;
import com.logicsignalprotector.apigateway.auth.service.PermissionService;
import com.logicsignalprotector.apigateway.internal.api.dto.InternalDtos;
import jakarta.validation.Valid;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/identity")
public class InternalIdentityController {

  private final ExternalAccountRepository externalAccounts;
  private final PermissionService permissionService;
  private final AuthAuditService audit;

  public InternalIdentityController(
      ExternalAccountRepository externalAccounts,
      PermissionService permissionService,
      AuthAuditService audit) {
    this.externalAccounts = externalAccounts;
    this.permissionService = permissionService;
    this.audit = audit;
  }

  /** Step 1.3: resolve our internal user by (providerCode, externalUserId). */
  @PostMapping("/resolve")
  @Transactional(readOnly = true)
  public InternalDtos.ResolveResponse resolve(@Valid @RequestBody InternalDtos.ResolveRequest req) {
    var opt =
        externalAccounts.findByProviderCodeAndExternalId(req.providerCode(), req.externalUserId());
    if (opt.isEmpty()) {
      return new InternalDtos.ResolveResponse(false, null, null, List.of(), List.of());
    }

    var user = opt.get().getUser();
    Long userId = user.getId();
    List<String> roles =
        user.getRoles().stream().map(r -> r.getCode()).sorted().collect(Collectors.toList());
    List<String> perms =
        permissionService.getEffectivePermissionCodes(userId).stream()
            .sorted()
            .collect(Collectors.toList());

    return new InternalDtos.ResolveResponse(true, userId, user.getLogin(), roles, perms);
  }

  /** Step 1.4: unlink external account by (providerCode, externalUserId). Idempotent. */
  @PostMapping("/unlink")
  @Transactional
  public InternalDtos.OkResponse unlink(@Valid @RequestBody InternalDtos.UnlinkRequest req) {
    var opt =
        externalAccounts.findByProviderCodeAndExternalId(req.providerCode(), req.externalUserId());
    if (opt.isPresent()) {
      var ext = opt.get();
      var user = ext.getUser();
      externalAccounts.delete(ext);
      audit.log(user, "TELEGRAM_UNLINK", "internal", "internal", null);
    }
    return new InternalDtos.OkResponse(true);
  }
}
