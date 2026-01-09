package com.logicsignalprotector.commandcenter.domain;

import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class CommandRegistry {

  private final Map<String, CommandDef> byCommand;
  private final Map<String, CommandDef> byCode;
  private final List<CommandDef> all;

  public CommandRegistry() {
    List<CommandDef> defs =
        List.of(
            new CommandDef(
                "help", "/help", "список команд", false, false, true, Set.of(), Set.of()),
            new CommandDef(
                "helpdev", "/helpdev", "dev-команды", true, false, true, set("DEVGOD"), Set.of()),
            new CommandDef(
                "commands",
                "/commands",
                "список команд и тумблеров",
                true,
                false,
                true,
                Set.of(),
                set("COMMANDS_TOGGLE", "DEVGOD")),
            new CommandDef(
                "command_toggle",
                "/command enable|disable <code>",
                "вкл/выкл команду",
                true,
                false,
                true,
                Set.of(),
                set("COMMANDS_TOGGLE", "DEVGOD")),
            new CommandDef(
                "user_delete",
                "/user delete <login|id>",
                "hard delete пользователя",
                true,
                true,
                true,
                set("DEVGOD", "USERS_HARD_DELETE"),
                Set.of()),
            new CommandDef(
                "login", "/login", "логин + привязка", false, true, true, Set.of(), Set.of()),
            new CommandDef(
                "register",
                "/register",
                "регистрация + привязка",
                false,
                true,
                true,
                Set.of(),
                Set.of()),
            new CommandDef(
                "logout",
                "/logout",
                "отвязка (с подтверждением)",
                false,
                true,
                true,
                Set.of(),
                Set.of()),
            new CommandDef("me", "/me", "статус привязки", false, true, true, Set.of(), Set.of()),
            new CommandDef(
                "market",
                "/market",
                "demo: market-data",
                false,
                true,
                true,
                set("MARKETDATA_READ"),
                Set.of()),
            new CommandDef(
                "alerts",
                "/alerts",
                "demo: alerts",
                false,
                true,
                true,
                set("ALERTS_READ"),
                Set.of()),
            new CommandDef(
                "broker",
                "/broker",
                "demo: broker",
                false,
                true,
                true,
                set("BROKER_READ"),
                Set.of()),
            new CommandDef(
                "trade",
                "/trade",
                "demo: broker trade",
                false,
                true,
                true,
                set("BROKER_TRADE"),
                Set.of()),
            new CommandDef(
                "adminlogin",
                "/adminlogin <code>",
                "dev: выдать роль ADMIN по коду",
                false,
                false,
                true,
                Set.of(),
                Set.of()),
            new CommandDef(
                "users",
                "/users",
                "список пользователей",
                false,
                true,
                true,
                set("ADMIN_USERS_PERMS_REVOKE"),
                Set.of()),
            new CommandDef(
                "user",
                "/user <login>",
                "детали пользователя",
                false,
                true,
                true,
                set("ADMIN_USERS_PERMS_REVOKE"),
                Set.of()),
            new CommandDef(
                "roles",
                "/roles",
                "список ролей",
                false,
                true,
                true,
                set("ADMIN_USERS_PERMS_REVOKE"),
                Set.of()),
            new CommandDef(
                "perms",
                "/perms",
                "список прав",
                false,
                true,
                true,
                set("ADMIN_USERS_PERMS_REVOKE"),
                Set.of()),
            new CommandDef(
                "grantrole",
                "/grantrole <login> <role>",
                "выдать роль пользователю",
                false,
                true,
                true,
                set("ADMIN_USERS_PERMS_REVOKE"),
                Set.of()),
            new CommandDef(
                "revokerole",
                "/revokerole <login> <role>",
                "отозвать роль у пользователя",
                false,
                true,
                true,
                set("ADMIN_USERS_PERMS_REVOKE"),
                Set.of()),
            new CommandDef(
                "grantperm",
                "/grantperm <login> <perm> [reason]",
                "выдать perm пользователю",
                false,
                true,
                true,
                set("ADMIN_USERS_PERMS_REVOKE"),
                Set.of()),
            new CommandDef(
                "denyperm",
                "/denyperm <login> <perm> [reason]",
                "запретить perm пользователю",
                false,
                true,
                true,
                set("ADMIN_USERS_PERMS_REVOKE"),
                Set.of()),
            new CommandDef(
                "revokeperm",
                "/revokeperm <login> <perm>",
                "снять override perm",
                false,
                true,
                true,
                set("ADMIN_USERS_PERMS_REVOKE"),
                Set.of()));

    Map<String, CommandDef> byCmd = new HashMap<>();
    Map<String, CommandDef> byCodeMap = new HashMap<>();
    for (CommandDef def : defs) {
      byCmd.put(def.command(), def);
      byCodeMap.put(def.code(), def);
    }

    this.all = List.copyOf(defs);
    this.byCommand = Collections.unmodifiableMap(byCmd);
    this.byCode = Collections.unmodifiableMap(byCodeMap);
  }

  public List<CommandDef> all() {
    return all;
  }

  public CommandDef byCommand(String command) {
    return byCommand.get(command);
  }

  public CommandDef byCode(String code) {
    return byCode.get(code);
  }

  private static Set<String> set(String... values) {
    if (values == null || values.length == 0) {
      return Set.of();
    }
    return Set.of(values);
  }

  public record CommandDef(
      String code,
      String command,
      String description,
      boolean devOnly,
      boolean toggleable,
      boolean showInHelp,
      Set<String> requiredAllPerms,
      Set<String> requiredAnyPerms) {}
}
