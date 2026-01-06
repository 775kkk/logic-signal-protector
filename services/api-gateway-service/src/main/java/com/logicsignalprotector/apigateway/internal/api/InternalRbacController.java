package com.logicsignalprotector.apigateway.internal.api;

import com.logicsignalprotector.apigateway.internal.api.dto.InternalRbacDtos;
import com.logicsignalprotector.apigateway.internal.service.RbacAdminService;
import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Step 1.4: internal RBAC admin API for command-center "console" commands. */
@RestController
@RequestMapping("/internal/rbac")
public class InternalRbacController {

  private final RbacAdminService rbac;

  public InternalRbacController(RbacAdminService rbac) {
    this.rbac = rbac;
  }

  // Dev-only: elevate current user to ADMIN by a shared code.
  @PostMapping("/elevate-by-code")
  @Transactional
  public InternalRbacDtos.ElevateByCodeResponse elevateByCode(
      @Valid @RequestBody InternalRbacDtos.ElevateByCodeRequest req) {
    return rbac.elevateByCode(req.providerCode(), req.externalUserId(), req.code());
  }

  @PostMapping("/users/get")
  @Transactional(readOnly = true)
  public InternalRbacDtos.UserInfoResponse getUser(
      @Valid @RequestBody InternalRbacDtos.ActorLoginRequest req) {
    return rbac.getUser(req.actorUserId(), req.login());
  }

  @PostMapping("/users/list")
  @Transactional(readOnly = true)
  public InternalRbacDtos.UsersListResponse listUsers(
      @Valid @RequestBody InternalRbacDtos.ActorRequest req) {
    return rbac.listUsers(req.actorUserId());
  }

  @PostMapping("/roles/list")
  @Transactional(readOnly = true)
  public InternalRbacDtos.RolesResponse listRoles(
      @Valid @RequestBody InternalRbacDtos.ActorRequest req) {
    return rbac.listRoles(req.actorUserId());
  }

  @PostMapping("/perms/list")
  @Transactional(readOnly = true)
  public InternalRbacDtos.PermsResponse listPerms(
      @Valid @RequestBody InternalRbacDtos.ActorRequest req) {
    return rbac.listPerms(req.actorUserId());
  }

  @PostMapping("/roles/grant")
  @Transactional
  public InternalRbacDtos.UserInfoResponse grantRole(
      @Valid @RequestBody InternalRbacDtos.ActorTargetRoleRequest req) {
    return rbac.grantRole(req.actorUserId(), req.targetLogin(), req.roleCode());
  }

  @PostMapping("/roles/revoke")
  @Transactional
  public InternalRbacDtos.UserInfoResponse revokeRole(
      @Valid @RequestBody InternalRbacDtos.ActorTargetRoleRequest req) {
    return rbac.revokeRole(req.actorUserId(), req.targetLogin(), req.roleCode());
  }

  @PostMapping("/perms/grant")
  @Transactional
  public InternalRbacDtos.UserInfoResponse grantPerm(
      @Valid @RequestBody InternalRbacDtos.ActorTargetPermRequest req) {
    return rbac.grantPerm(
        req.actorUserId(), req.targetLogin(), req.permCode(), req.reason(), req.expiresAt());
  }

  @PostMapping("/perms/deny")
  @Transactional
  public InternalRbacDtos.UserInfoResponse denyPerm(
      @Valid @RequestBody InternalRbacDtos.ActorTargetPermRequest req) {
    return rbac.denyPerm(
        req.actorUserId(), req.targetLogin(), req.permCode(), req.reason(), req.expiresAt());
  }

  @PostMapping("/perms/revoke")
  @Transactional
  public InternalRbacDtos.UserInfoResponse revokePerm(
      @Valid @RequestBody InternalRbacDtos.ActorTargetPermRequest req) {
    return rbac.revokePerm(req.actorUserId(), req.targetLogin(), req.permCode());
  }
}
