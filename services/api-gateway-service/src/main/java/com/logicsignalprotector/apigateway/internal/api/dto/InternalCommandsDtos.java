package com.logicsignalprotector.apigateway.internal.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

/** Step 1.5: internal command switches DTOs. */
public final class InternalCommandsDtos {
  private InternalCommandsDtos() {}

  public record CommandSwitchDto(
      String commandCode, boolean enabled, Instant updatedAt, Long updatedByUserId, String note) {}

  public record ListCommandSwitchesResponse(List<CommandSwitchDto> switches) {}

  public record SetEnabledRequest(
      @NotNull Long actorUserId,
      @NotBlank String commandCode,
      @NotNull Boolean enabled,
      String note) {}

  public record SetEnabledResponse(CommandSwitchDto value) {}
}
