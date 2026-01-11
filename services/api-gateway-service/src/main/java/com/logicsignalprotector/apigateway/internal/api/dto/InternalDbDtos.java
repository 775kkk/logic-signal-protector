package com.logicsignalprotector.apigateway.internal.api.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/** Internal DB console DTOs (dev-only, guarded by X-Internal-Token). */
public final class InternalDbDtos {
  private InternalDbDtos() {}

  public record DbQueryRequest(@NotBlank String sql, Integer maxRows) {}

  public record DbQueryResponse(
      boolean ok,
      String type,
      List<String> columns,
      List<List<String>> rows,
      Long updated,
      boolean truncated,
      String error) {}
}
