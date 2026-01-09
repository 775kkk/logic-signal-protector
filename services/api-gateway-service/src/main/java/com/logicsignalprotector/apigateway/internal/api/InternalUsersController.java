package com.logicsignalprotector.apigateway.internal.api;

import com.logicsignalprotector.apigateway.internal.api.dto.InternalUsersDtos;
import com.logicsignalprotector.apigateway.internal.service.UserHardDeleteService;
import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Step 1.5: internal users admin API. */
@RestController
@RequestMapping("/internal/users")
public class InternalUsersController {

  private final UserHardDeleteService hardDelete;

  public InternalUsersController(UserHardDeleteService hardDelete) {
    this.hardDelete = hardDelete;
  }

  @PostMapping("/hard-delete")
  @Transactional
  public InternalUsersDtos.HardDeleteResponse hardDelete(
      @Valid @RequestBody InternalUsersDtos.HardDeleteRequest req) {
    return hardDelete.hardDelete(req.actorUserId(), req.targetUserId(), req.targetLogin());
  }
}
