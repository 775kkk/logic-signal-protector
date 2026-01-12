package com.logicsignalprotector.apigateway.internal.api.dto;

import jakarta.validation.constraints.NotNull;

/** Step 1.5: internal users admin DTOs. */
public final class InternalUsersDtos {
  private InternalUsersDtos() {}

  public record HardDeleteRequest(
      @NotNull Long actorUserId, Long targetUserId, String targetLogin) {}

  public record HardDeleteResponse(boolean ok, Long deletedUserId, String deletedLogin) {}
}
