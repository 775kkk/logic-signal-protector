package com.logicsignalprotector.apigateway.internal.api;

import com.logicsignalprotector.apigateway.internal.api.dto.InternalCommandsDtos;
import com.logicsignalprotector.apigateway.internal.service.CommandSwitchService;
import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Step 1.5: internal commands switches API. */
@RestController
@RequestMapping("/internal/commands")
public class InternalCommandsController {

  private final CommandSwitchService commands;

  public InternalCommandsController(CommandSwitchService commands) {
    this.commands = commands;
  }

  @GetMapping("/list")
  @Transactional(readOnly = true)
  public InternalCommandsDtos.ListCommandSwitchesResponse list() {
    return commands.listAll();
  }

  @PostMapping("/set-enabled")
  @Transactional
  public InternalCommandsDtos.SetEnabledResponse setEnabled(
      @Valid @RequestBody InternalCommandsDtos.SetEnabledRequest req) {
    return commands.setEnabled(req.actorUserId(), req.commandCode(), req.enabled(), req.note());
  }
}
