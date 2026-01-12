package com.logicsignalprotector.apigateway.internal.api;

import com.logicsignalprotector.apigateway.internal.api.dto.InternalDbDtos;
import com.logicsignalprotector.apigateway.internal.service.DbConsoleService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal DB console endpoint (dev-only, guarded by X-Internal-Token). */
@RestController
@RequestMapping("/internal/db")
public class InternalDbController {

  private final DbConsoleService console;

  public InternalDbController(DbConsoleService console) {
    this.console = console;
  }

  @PostMapping("/query")
  public InternalDbDtos.DbQueryResponse query(
      @Valid @RequestBody InternalDbDtos.DbQueryRequest req) {
    return console.execute(req.sql(), req.maxRows());
  }
}
