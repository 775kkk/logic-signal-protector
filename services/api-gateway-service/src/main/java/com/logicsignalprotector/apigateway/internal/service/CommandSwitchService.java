package com.logicsignalprotector.apigateway.internal.service;

import com.logicsignalprotector.apigateway.auth.service.AuthAuditService;
import com.logicsignalprotector.apigateway.auth.service.PermissionService;
import com.logicsignalprotector.apigateway.common.web.ForbiddenException;
import com.logicsignalprotector.apigateway.internal.api.dto.InternalCommandsDtos;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CommandSwitchService {

  private static final String PERM_COMMANDS_TOGGLE = "COMMANDS_TOGGLE";
  private static final String PERM_DEVGOD = "DEVGOD";

  private final JdbcTemplate jdbc;
  private final PermissionService permissions;
  private final AuthAuditService audit;

  @Transactional(readOnly = true)
  public InternalCommandsDtos.ListCommandSwitchesResponse listAll() {
    String sql =
        "SELECT command_code, enabled, updated_at, updated_by_user_id, note "
            + "FROM command_switches "
            + "ORDER BY command_code";
    List<InternalCommandsDtos.CommandSwitchDto> list =
        jdbc.query(
            sql,
            (rs, rowNum) ->
                new InternalCommandsDtos.CommandSwitchDto(
                    rs.getString(1),
                    rs.getBoolean(2),
                    rs.getObject(3, java.time.Instant.class),
                    rs.getObject(4, Long.class),
                    rs.getString(5)));
    return new InternalCommandsDtos.ListCommandSwitchesResponse(list);
  }

  @Transactional
  public InternalCommandsDtos.SetEnabledResponse setEnabled(
      Long actorUserId, String commandCode, boolean enabled, String note) {
    requireTogglePerm(actorUserId);

    String sql =
        "INSERT INTO command_switches(command_code, enabled, updated_at, updated_by_user_id, note) "
            + "VALUES(?, ?, NOW(), ?, ?) "
            + "ON CONFLICT(command_code) DO UPDATE SET "
            + "  enabled = EXCLUDED.enabled, "
            + "  updated_at = NOW(), "
            + "  updated_by_user_id = EXCLUDED.updated_by_user_id, "
            + "  note = EXCLUDED.note "
            + "RETURNING command_code, enabled, updated_at, updated_by_user_id, note";

    InternalCommandsDtos.CommandSwitchDto dto =
        jdbc.queryForObject(
            sql,
            (rs, rowNum) ->
                new InternalCommandsDtos.CommandSwitchDto(
                    rs.getString(1),
                    rs.getBoolean(2),
                    rs.getObject(3, java.time.Instant.class),
                    rs.getObject(4, Long.class),
                    rs.getString(5)),
            commandCode,
            enabled,
            actorUserId,
            trimOrNull(note));

    audit.log(
        null,
        "COMMAND_SWITCH_SET",
        "internal",
        "internal",
        "{\"actorUserId\":"
            + actorUserId
            + ",\"commandCode\":\""
            + escape(commandCode)
            + "\",\"enabled\":"
            + enabled
            + "}");

    return new InternalCommandsDtos.SetEnabledResponse(dto);
  }

  private void requireTogglePerm(Long actorUserId) {
    if (actorUserId == null) {
      throw new IllegalArgumentException("actorUserId is required");
    }
    Set<String> perms = permissions.getEffectivePermissionCodes(actorUserId);
    if (!perms.contains(PERM_DEVGOD) && !perms.contains(PERM_COMMANDS_TOGGLE)) {
      throw new ForbiddenException("Missing permission " + PERM_COMMANDS_TOGGLE);
    }
  }

  private static String trimOrNull(String s) {
    if (s == null) return null;
    String t = s.trim();
    return t.isBlank() ? null : t.substring(0, Math.min(t.length(), 256));
  }

  private static String escape(String s) {
    if (s == null) return "";
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
