package com.logicsignalprotector.commandcenter.domain;

import com.logicsignalprotector.commandcenter.api.dto.ChatMessageEnvelope;
import com.logicsignalprotector.commandcenter.api.dto.ChatResponse;
import com.logicsignalprotector.commandcenter.api.dto.InlineKeyboard;
import com.logicsignalprotector.commandcenter.api.dto.OutgoingMessage;
import com.logicsignalprotector.commandcenter.client.DownstreamClients;
import com.logicsignalprotector.commandcenter.client.GatewayInternalClient;
import com.logicsignalprotector.commandcenter.domain.CommandRegistry.CommandDef;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

/**
 * Step 1.3-1.5: core "brain" for chat commands.
 *
 * <p>Input: raw user message in a universal envelope. Output: a list of texts to send back to the
 * user.
 */
@Service
@Slf4j
public class ChatCommandHandler {

  private static final String PERM_ADMIN_ANSWERS_LOG = "ADMIN_ANSWERS_LOG";
  private static final String PERM_ADMIN_MANAGE = "ADMIN_USERS_PERMS_REVOKE";
  private static final String PERM_COMMANDS_TOGGLE = "COMMANDS_TOGGLE";
  private static final String PERM_DEVGOD = "DEVGOD";
  private static final String PERM_USERS_HARD_DELETE = "USERS_HARD_DELETE";

  private static final int COMMANDS_PAGE_SIZE = 10;
  private static final int DB_DEFAULT_MAX_ROWS = 50;
  private static final int DB_TABLES_MAX_ROWS = 200;
  private static final String DB_TABLES_SQL =
      "select table_schema, table_name from information_schema.tables "
          + "where table_schema not in ('pg_catalog','information_schema') "
          + "order by table_schema, table_name";
  private static final String DB_HISTORY_SQL =
      "select installed_rank, version, description, type, script, checksum, installed_by, "
          + "installed_on, execution_time, success from flyway_schema_history "
          + "order by installed_rank";

  private final GatewayInternalClient gateway;
  private final DownstreamClients downstream;
  private final ChatStateStore stateStore;
  private final AdminCodeRateLimiter adminCodeLimiter;
  private final CommandRegistry registry;
  private final CommandSwitchCache switches;
  private final Duration logoutConfirmTtl;
  private final Duration hardDeleteConfirmTtl;
  private final boolean devConsoleEnabled;

  private final Map<String, String> aliases = buildAliases();
  private final TextTable textTable = new TextTable();

  public ChatCommandHandler(
      GatewayInternalClient gateway,
      DownstreamClients downstream,
      ChatStateStore stateStore,
      AdminCodeRateLimiter adminCodeLimiter,
      CommandRegistry registry,
      CommandSwitchCache switches,
      @Value("${chat.logout.confirm-ttl:PT60S}") Duration logoutConfirmTtl,
      @Value("${chat.hard-delete.confirm-ttl:PT60S}") Duration hardDeleteConfirmTtl,
      @Value("${dev.console.enabled:false}") boolean devConsoleEnabled) {
    this.gateway = gateway;
    this.downstream = downstream;
    this.stateStore = stateStore;
    this.adminCodeLimiter = adminCodeLimiter;
    this.registry = registry;
    this.switches = switches;
    this.logoutConfirmTtl = logoutConfirmTtl;
    this.hardDeleteConfirmTtl = hardDeleteConfirmTtl;
    this.devConsoleEnabled = devConsoleEnabled;
  }

  public ChatResponse handle(ChatMessageEnvelope env) {
    String input = extractInput(env);
    if (input.isBlank()) {
      return ChatResponse.ofText("Пустое сообщение.");
    }

    String normalized = normalizeInput(input);
    normalized = expandUnderscoreCommands(normalized);
    String key = stateKey(env);

    ChatStateStore.StateEntry entry =
        stateStore.get(key).orElse(new ChatStateStore.StateEntry(ChatState.NONE, null, null));
    ChatState st = entry.state();

    // cancel should work in any state
    if (isCancel(normalized)) {
      stateStore.clear(key);
      return ChatResponse.ofText("Ок, отменено.");
    }

    // special state: logout confirmation
    if (st == ChatState.AWAIT_LOGOUT_CONFIRM) {
      return handleLogoutConfirm(env, key, normalized);
    }

    // special state: hard delete confirmation
    if (st == ChatState.AWAIT_USER_HARD_DELETE_CONFIRM) {
      return handleHardDeleteConfirm(env, key, entry.payload(), normalized);
    }

    // If we're waiting for credentials, accept plain "login password" as the next input.
    if (st == ChatState.AWAIT_LOGIN_CREDENTIALS && !normalized.startsWith("/")) {
      if (!looksLikeCredentials(normalized)) {
        return ChatResponse.ofText("Нужно 2 значения: <login> <password> (или /cancel)");
      }
      return doLoginAndLink(env, key, normalized);
    }
    if (st == ChatState.AWAIT_REGISTER_CREDENTIALS && !normalized.startsWith("/")) {
      if (!looksLikeCredentials(normalized)) {
        return ChatResponse.ofText("Нужно 2 значения: <login> <password> (или /cancel)");
      }
      return doRegisterAndLink(env, key, normalized);
    }

    Parsed p = parse(normalized);
    boolean isUserDelete = "/user".equals(p.cmd()) && "delete".equalsIgnoreCase(p.arg1());
    CommandDef def = isUserDelete ? registry.byCode("user_delete") : registry.byCommand(p.cmd());

    if (def == null) {
      return ChatResponse.ofText("Не понял команду. /help");
    }
    if (def.devOnly() && !devConsoleEnabled) {
      return ChatResponse.ofText("Команда недоступна.");
    }
    if (def.toggleable() && !switches.isEnabled(def.code())) {
      return ChatResponse.ofText("Команда отключена.");
    }

    return switch (p.cmd()) {
      case "/start", "/help" -> doHelp(env);
      case "/helpdev" -> doHelpDev(env);
      case "/commands" -> doCommands(env, p);
      case "/command" -> doCommandToggle(env, p);
      case "/login" -> handleLoginCommand(env, key, p);
      case "/register" -> handleRegisterCommand(env, key, p);
      case "/logout" -> handleLogoutRequest(env, key, p);
      case "/me" -> doMe(env);
      case "/db_menu" -> doDbMenu(env);
      case "/db" -> doDb(env, normalized);
      case "/market" -> doMarket(env, p);
      case "/alerts" -> doProtectedCall(env, "alerts", "ALERTS_READ");
      case "/broker" -> doProtectedCall(env, "broker", "BROKER_READ");
      case "/trade" -> doProtectedCall(env, "trade", "BROKER_TRADE");

      // Step 1.4 admin console commands
      case "/adminlogin" -> doAdminLogin(env, p);
      case "/user" -> isUserDelete ? doUserHardDelete(env, key, p) : doAdminUser(env, p);
      case "/users" -> doAdminUsers(env);
      case "/roles" -> doAdminRoles(env);
      case "/perms" -> doAdminPerms(env);
      case "/grantrole" -> doAdminGrantRole(env, p);
      case "/revokerole" -> doAdminRevokeRole(env, p);
      case "/grantperm" -> doAdminGrantPerm(env, p, true);
      case "/denyperm" -> doAdminGrantPerm(env, p, false);
      case "/revokeperm" -> doAdminRevokePerm(env, p);

      default -> ChatResponse.ofText("Не понял команду. /help");
    };
  }

  /* =========================
  Command handlers
  ========================= */

  private ChatResponse doHelp(ChatMessageEnvelope env) {
    try {
      var res = gateway.resolve(providerCode(env), env.externalUserId());
      Set<String> perms =
          res != null && res.perms() != null ? new HashSet<>(res.perms()) : Set.of();
      boolean linked = res != null && res.linked();

      List<CommandDef> visible =
          registry.all().stream()
              .filter(CommandDef::showInHelp)
              .filter(def -> !def.devOnly() || devConsoleEnabled)
              .filter(def -> !def.toggleable() || switches.isEnabled(def.code()))
              .filter(def -> isPermitted(def, perms, linked))
              .sorted(Comparator.comparing(CommandDef::command))
              .toList();

      String text = "Команды:\n" + formatCommandList(visible);
      return ChatResponse.ofText(text);
    } catch (RestClientResponseException e) {
      return ChatResponse.ofText(formatError(canSeeRaw(env), "help", e));
    }
  }

  private ChatResponse doHelpDev(ChatMessageEnvelope env) {
    try {
      var res = gateway.resolve(providerCode(env), env.externalUserId());
      if (!res.linked()) {
        return ChatResponse.ofText("Сначала привяжи аккаунт: /login или /register.");
      }
      if (!res.perms().contains(PERM_DEVGOD)) {
        return ChatResponse.ofText("Нет прав для операции.");
      }

      List<CommandDef> visible =
          registry.all().stream()
              .filter(CommandDef::devOnly)
              .filter(def -> !"helpdev".equals(def.code()))
              .filter(CommandDef::showInHelp)
              .filter(def -> !def.toggleable() || switches.isEnabled(def.code()))
              .sorted(Comparator.comparing(CommandDef::command))
              .toList();

      String text = "Dev-команды:\n" + formatCommandList(visible);
      return ChatResponse.ofText(text);
    } catch (RestClientResponseException e) {
      return ChatResponse.ofText(formatError(canSeeRaw(env), "helpdev", e));
    }
  }

  private ChatResponse doCommands(ChatMessageEnvelope env, Parsed p) {
    try {
      var res = gateway.resolve(providerCode(env), env.externalUserId());
      if (!res.linked()) {
        return ChatResponse.ofText("Сначала привяжи аккаунт: /login или /register.");
      }
      if (!hasAnyPerm(res.perms(), Set.of(PERM_COMMANDS_TOGGLE, PERM_DEVGOD))) {
        return ChatResponse.ofText("Нет прав для операции.");
      }

      int page = parsePage(p.arg1());
      List<CommandDef> all = new ArrayList<>(registry.all());
      all.sort(Comparator.comparing(CommandDef::code));

      int totalPages = Math.max(1, (int) Math.ceil(all.size() / (double) COMMANDS_PAGE_SIZE));
      page = Math.max(1, Math.min(page, totalPages));

      int from = (page - 1) * COMMANDS_PAGE_SIZE;
      int to = Math.min(from + COMMANDS_PAGE_SIZE, all.size());
      List<CommandDef> slice = all.subList(from, to);

      String header = "Commands page " + page + "/" + totalPages;
      String text = header + "\n" + formatCommandsPage(slice);

      OutgoingMessage msg = OutgoingMessage.plain(text).preferEdit();
      InlineKeyboard keyboard = buildCommandsPager(page, totalPages);
      if (keyboard != null) {
        msg = msg.withKeyboard(keyboard);
      }
      return ChatResponse.of(msg);

    } catch (RestClientResponseException e) {
      return ChatResponse.ofText(formatError(canSeeRaw(env), "commands", e));
    }
  }

  private ChatResponse doCommandToggle(ChatMessageEnvelope env, Parsed p) {
    String action = p.arg1();
    String code = p.arg2();
    String note = p.arg3();

    if (action == null || code == null) {
      return ChatResponse.ofText("Использование: /command enable|disable <code> [note]");
    }

    boolean enabled;
    if ("enable".equalsIgnoreCase(action)) {
      enabled = true;
    } else if ("disable".equalsIgnoreCase(action)) {
      enabled = false;
    } else {
      return ChatResponse.ofText("Использование: /command enable|disable <code> [note]");
    }

    code = code.trim();
    if (code.startsWith("/")) {
      CommandDef byCmd = registry.byCommand(code);
      if (byCmd != null) {
        code = byCmd.code();
      }
    }
    code = code.toLowerCase(Locale.ROOT);

    CommandDef target = registry.byCode(code);
    if (target == null) {
      return ChatResponse.ofText("Неизвестная команда: " + code);
    }
    if (!target.toggleable()) {
      return ChatResponse.ofText("Эту команду нельзя отключить.");
    }

    try {
      var res = gateway.resolve(providerCode(env), env.externalUserId());
      if (!res.linked()) {
        return ChatResponse.ofText("Сначала привяжи аккаунт: /login или /register.");
      }
      if (!hasAnyPerm(res.perms(), Set.of(PERM_COMMANDS_TOGGLE, PERM_DEVGOD))) {
        return ChatResponse.ofText("Нет прав для операции.");
      }

      gateway.setCommandEnabled(res.userId(), target.code(), enabled, note);
      return doCommands(env, new Parsed("/commands", "page=1", null, null));
    } catch (RestClientResponseException e) {
      return ChatResponse.ofText(formatError(canSeeRaw(env), "command", e));
    }
  }

  private ChatResponse handleLoginCommand(ChatMessageEnvelope env, String key, Parsed p) {
    if (p.arg1() == null || p.arg2() == null) {
      stateStore.set(key, ChatState.AWAIT_LOGIN_CREDENTIALS);
      return ChatResponse.ofText(
          "Логин: отправь сообщение в формате: <login> <password> (или /cancel)");
    }
    stateStore.clear(key);
    return doLoginAndLink(env, key, p.arg1() + " " + p.arg2());
  }

  private ChatResponse handleRegisterCommand(ChatMessageEnvelope env, String key, Parsed p) {
    if (p.arg1() == null || p.arg2() == null) {
      stateStore.set(key, ChatState.AWAIT_REGISTER_CREDENTIALS);
      return ChatResponse.ofText(
          "Регистрация: отправь сообщение в формате: <login> <password> (или /cancel)");
    }
    stateStore.clear(key);
    return doRegisterAndLink(env, key, p.arg1() + " " + p.arg2());
  }

  private ChatResponse handleLogoutRequest(ChatMessageEnvelope env, String key, Parsed p) {
    // confirmation required
    stateStore.set(key, ChatState.AWAIT_LOGOUT_CONFIRM, logoutConfirmTtl);
    return ChatResponse.ofText(
        String.join(
            "\n",
            "Подтверди logout:",
            "- отправь: logout yes (или logout да)",
            "- отмена: cancel (или отмена)"));
  }

  private ChatResponse handleLogoutConfirm(ChatMessageEnvelope env, String key, String text) {
    Parsed p = parse(text);

    boolean confirmed = false;
    if ("/logout".equals(p.cmd())
        && p.arg1() != null
        && ("yes".equalsIgnoreCase(p.arg1()) || "да".equalsIgnoreCase(p.arg1()))) {
      confirmed = true;
    }
    // allow plain "yes" as convenience
    if (!confirmed
        && !text.startsWith("/")
        && ("yes".equalsIgnoreCase(text.trim()) || "да".equalsIgnoreCase(text.trim()))) {
      confirmed = true;
    }

    if (!confirmed) {
      return ChatResponse.ofText(
          "Жду подтверждение: logout yes (или logout да). Отмена: cancel/отмена");
    }

    try {
      gateway.unlink(providerCode(env), env.externalUserId());
      stateStore.clear(key);
      return ChatResponse.ofText(
          "Ок. Привязка удалена (logout). Используй /login чтобы привязать заново.");
    } catch (RestClientResponseException e) {
      boolean canRaw = canSeeRaw(env);
      return ChatResponse.ofText(formatError(canRaw, "logout", e));
    }
  }

  private ChatResponse handleHardDeleteConfirm(
      ChatMessageEnvelope env, String key, String payload, String text) {
    if (payload == null || payload.isBlank()) {
      stateStore.clear(key);
      return ChatResponse.ofText("Подтверждение истекло.");
    }

    String trimmed = text.trim();
    if (!trimmed.toUpperCase(Locale.ROOT).startsWith("DELETE ")) {
      return ChatResponse.ofText("Неверное подтверждение. Ожидаю: DELETE " + payload);
    }

    String target = trimmed.substring("DELETE ".length()).trim();
    if (!target.equalsIgnoreCase(payload)) {
      return ChatResponse.ofText("Неверное подтверждение. Ожидаю: DELETE " + payload);
    }

    try {
      var res = gateway.resolve(providerCode(env), env.externalUserId());
      if (!res.linked()) {
        return ChatResponse.ofText("Сначала привяжи аккаунт: /login или /register.");
      }
      if (!hasAllPerm(res.perms(), Set.of(PERM_DEVGOD, PERM_USERS_HARD_DELETE))) {
        return ChatResponse.ofText("Нет прав для операции.");
      }

      Long targetId = parseLongOrNull(target);
      gateway.hardDeleteUser(res.userId(), targetId, targetId == null ? target : null);
      stateStore.clear(key);
      return ChatResponse.ofText("Удалено: " + target);
    } catch (RestClientResponseException e) {
      return ChatResponse.ofText(formatError(canSeeRaw(env), "hard-delete", e));
    }
  }

  private ChatResponse doUserHardDelete(ChatMessageEnvelope env, String key, Parsed p) {
    if (p.arg2() == null || p.arg2().isBlank()) {
      return ChatResponse.ofText("Использование: /user delete <login|id>");
    }
    try {
      var res = gateway.resolve(providerCode(env), env.externalUserId());
      if (!res.linked()) {
        return ChatResponse.ofText("Сначала привяжи аккаунт: /login или /register.");
      }
      if (!hasAllPerm(res.perms(), Set.of(PERM_DEVGOD, PERM_USERS_HARD_DELETE))) {
        return ChatResponse.ofText("Нет прав для операции.");
      }

      String target = p.arg2().trim();
      stateStore.set(key, ChatState.AWAIT_USER_HARD_DELETE_CONFIRM, target, hardDeleteConfirmTtl);
      return ChatResponse.ofText("Подтверди: DELETE " + target);
    } catch (RestClientResponseException e) {
      return ChatResponse.ofText(formatError(canSeeRaw(env), "user_delete", e));
    }
  }

  private ChatResponse doMe(ChatMessageEnvelope env) {
    try {
      var res = gateway.resolve(providerCode(env), env.externalUserId());
      if (!res.linked()) {
        return ChatResponse.ofText("Аккаунт не привязан. Используй /login или /register.");
      }

      boolean revealDetails = canRevealAccountDetails(res);
      if (!revealDetails) {
        return ChatResponse.ofText("Привязка: активна\nЛогин: " + safe(res.login()));
      }

      return ChatResponse.ofText(
          "Привязка: активна\nUser ID: "
              + res.userId()
              + "\nЛогин: "
              + safe(res.login())
              + "\nРоли: "
              + res.roles()
              + "\nПрава: "
              + res.perms());
    } catch (RestClientResponseException e) {
      return ChatResponse.ofText(formatError(canSeeRaw(env), "me", e));
    }
  }

  private ChatResponse doDbMenu(ChatMessageEnvelope env) {
    try {
      var res = gateway.resolve(providerCode(env), env.externalUserId());
      if (!isDevAdmin(res)) {
        return ChatResponse.ofText("DB меню недоступно. Проверь dev-права.");
      }
      String text =
          String.join(
              "\n",
              "DB консоль (dev-only):",
              "- /db <SQL> - выполнить SQL",
              "- /db_tables - список таблиц",
              "- /db_describe <schema.table> - структура таблицы",
              "- /db_history - flyway_schema_history",
              "Результаты ограничены первыми "
                  + DB_DEFAULT_MAX_ROWS
                  + " строками (используй LIMIT).");
      return ChatResponse.ofText(text);
    } catch (RestClientResponseException e) {
      return ChatResponse.ofText(formatError(canSeeRaw(env), "db_menu", e));
    }
  }

  private ChatResponse doDb(ChatMessageEnvelope env, String input) {
    try {
      var res = gateway.resolve(providerCode(env), env.externalUserId());
      if (!isDevAdmin(res)) {
        return ChatResponse.ofText("DB команда недоступна. Проверь dev-права.");
      }
      DbPlan plan = resolveDbPlan(input);
      if (plan == null || plan.sql() == null || plan.sql().isBlank()) {
        String msg = plan == null ? "Нужен SQL." : plan.error();
        return ChatResponse.ofText(
            (msg == null || msg.isBlank() ? "Нужен SQL." : msg) + " /db_menu");
      }
      var resp = gateway.dbQuery(plan.sql(), plan.maxRows());
      if (resp == null || !resp.ok()) {
        String error = resp == null ? null : resp.error();
        return ChatResponse.ofText("Ошибка SQL: " + safeDbError(error));
      }
      String type = resp.type();
      if (type != null && type.equalsIgnoreCase("UPDATE")) {
        String message =
            resp.updated() == null ? "Готово." : "Готово. Затронуто строк: " + resp.updated();
        return ChatResponse.ofText(message);
      }

      List<String> columns = resp.columns() == null ? List.of() : resp.columns();
      List<List<String>> rows = resp.rows() == null ? List.of() : resp.rows();
      if (columns.isEmpty()) {
        return ChatResponse.ofText("Нет данных.");
      }
      String table = textTable.render(columns, rows);
      StringBuilder sb = new StringBuilder();
      if (resp.truncated()) {
        sb.append("Показаны первые ").append(plan.maxRows()).append(" строк. Используй LIMIT.\n");
      }
      sb.append(table);
      return ChatResponse.of(OutgoingMessage.pre(sb.toString()));
    } catch (RestClientResponseException e) {
      return ChatResponse.ofText(formatError(canSeeRaw(env), "db", e));
    }
  }

  private ChatResponse doLoginAndLink(ChatMessageEnvelope env, String key, String text) {
    String[] tokens = text.trim().split("\\s+", 2);
    if (tokens.length < 2) {
      stateStore.set(key, ChatState.AWAIT_LOGIN_CREDENTIALS);
      return ChatResponse.ofText("Нужно 2 значения: <login> <password> (или /cancel)");
    }

    try {
      var t = gateway.loginAndLink(providerCode(env), env.externalUserId(), tokens[0], tokens[1]);
      stateStore.clear(key);

      boolean canRaw = t != null && t.perms() != null && t.perms().contains(PERM_ADMIN_ANSWERS_LOG);
      OutgoingMessage msg;
      if (!canRaw) {
        msg =
            OutgoingMessage.plain(
                    "Ок. Привязка завершена. login=" + safe(t == null ? null : t.login()))
                .deleteSourceMessage();
      } else {
        msg =
            OutgoingMessage.plain(
                    "Ок. Привязка завершена.\nlogin=" + safe(t.login()) + "\nperms=" + t.perms())
                .deleteSourceMessage();
      }
      msg = msg.withKeyboard(buildMainMenuKeyboard());
      return ChatResponse.of(msg);
    } catch (RestClientResponseException e) {
      stateStore.set(key, ChatState.AWAIT_LOGIN_CREDENTIALS);
      return ChatResponse.ofText(formatError(canSeeRaw(env), "login", e));
    }
  }

  private ChatResponse doRegisterAndLink(ChatMessageEnvelope env, String key, String text) {
    String[] tokens = text.trim().split("\\s+", 2);
    if (tokens.length < 2) {
      stateStore.set(key, ChatState.AWAIT_REGISTER_CREDENTIALS);
      return ChatResponse.ofText("Нужно 2 значения: <login> <password> (или /cancel)");
    }

    try {
      var t =
          gateway.registerAndLink(providerCode(env), env.externalUserId(), tokens[0], tokens[1]);
      stateStore.clear(key);

      boolean canRaw = t != null && t.perms() != null && t.perms().contains(PERM_ADMIN_ANSWERS_LOG);
      OutgoingMessage msg;
      if (!canRaw) {
        msg =
            OutgoingMessage.plain(
                    "Ок. Пользователь создан и привязан. login="
                        + safe(t == null ? null : t.login()))
                .deleteSourceMessage();
      } else {
        msg =
            OutgoingMessage.plain(
                    "Ок. Пользователь создан и привязан.\nlogin="
                        + safe(t.login())
                        + "\nperms="
                        + t.perms())
                .deleteSourceMessage();
      }
      msg = msg.withKeyboard(buildMainMenuKeyboard());
      return ChatResponse.of(msg);
    } catch (RestClientResponseException e) {
      stateStore.set(key, ChatState.AWAIT_REGISTER_CREDENTIALS);
      return ChatResponse.ofText(formatError(canSeeRaw(env), "register", e));
    }
  }

  private ChatResponse doProtectedCall(ChatMessageEnvelope env, String kind, String requiredPerm) {
    try {
      var res = gateway.resolve(providerCode(env), env.externalUserId());
      if (!res.linked()) {
        return ChatResponse.ofText("Сначала привяжи аккаунт: /login или /register.");
      }
      if (requiredPerm != null && !res.perms().contains(requiredPerm)) {
        return ChatResponse.ofText("Нет прав для операции.");
      }

      boolean canRaw = res.perms().contains(PERM_ADMIN_ANSWERS_LOG);
      var tokens = gateway.issueAccess(providerCode(env), env.externalUserId());
      if (tokens == null || tokens.accessToken() == null || tokens.accessToken().isBlank()) {
        return ChatResponse.ofText("Не удалось получить access token.");
      }

      Map<String, Object> out;
      switch (kind) {
        case "alerts" -> out = downstream.alertsSecureSample(tokens.accessToken());
        case "broker" -> out = downstream.brokerSecureSample(tokens.accessToken());
        case "trade" -> out = downstream.brokerTradeSample(tokens.accessToken());
        default -> out = Map.of("error", "unknown kind");
      }

      if (!canRaw) {
        return ChatResponse.ofText("Ок. Команда выполнена: " + kind);
      }
      return ChatResponse.ofText("Ответ " + kind + ":\n" + Objects.toString(out));

    } catch (RestClientResponseException e) {
      return ChatResponse.ofText(formatError(canSeeRaw(env), kind, e));
    } catch (Exception e) {
      return ChatResponse.ofText("Ошибка: " + e.getMessage());
    }
  }

  private ChatResponse doMarket(ChatMessageEnvelope env, Parsed p) {
    String sub = p.arg1() == null ? "help" : p.arg1().toLowerCase(Locale.ROOT);
    if ("help".equals(sub) || "h".equals(sub)) {
      return ChatResponse.ofText(marketHelpText());
    }

    try {
      var res = gateway.resolve(providerCode(env), env.externalUserId());
      if (!res.linked()) {
        return ChatResponse.ofText("Сначала привяжи аккаунт: /login или /register.");
      }
      if (!res.perms().contains("MARKETDATA_READ")) {
        return ChatResponse.ofText("Нет прав для операции.");
      }

      var tokens = gateway.issueAccess(providerCode(env), env.externalUserId());
      if (tokens == null || tokens.accessToken() == null || tokens.accessToken().isBlank()) {
        return ChatResponse.ofText("Не удалось получить access token.");
      }

      Map<String, String> opts = parseOptions(p.arg2(), p.arg3());
      String engine = opt(opts, "engine", "stock");
      String market = opt(opts, "market", "shares");
      String board = opt(opts, "board", "TQBR");

      return switch (sub) {
        case "instruments" ->
            marketInstruments(env, tokens.accessToken(), p, opts, engine, market, board);
        case "quote" -> marketQuote(env, tokens.accessToken(), p, opts, engine, market, board);
        case "candles" -> marketCandles(env, tokens.accessToken(), p, opts, engine, market, board);
        case "orderbook" ->
            marketOrderBook(env, tokens.accessToken(), p, opts, engine, market, board);
        case "trades" -> marketTrades(env, tokens.accessToken(), p, opts, engine, market, board);
        default ->
            ChatResponse.ofText("Неизвестная подкоманда: " + sub + "\n\n" + marketHelpText());
      };
    } catch (RestClientResponseException e) {
      return ChatResponse.ofText(formatError(canSeeRaw(env), "market/" + sub, e));
    } catch (Exception e) {
      return ChatResponse.ofText("Ошибка: " + e.getMessage());
    }
  }

  private ChatResponse marketInstruments(
      ChatMessageEnvelope env,
      String token,
      Parsed p,
      Map<String, String> opts,
      String engine,
      String market,
      String board) {
    String filter = null;
    if (p.arg2() != null && !p.arg2().contains("=")) {
      filter = p.arg2();
    } else {
      filter = opts.get("filter");
    }

    Integer limit = parseInt(opts.get("limit"));
    if (limit == null) limit = 10;
    if (limit < 1 || limit > 100) {
      return ChatResponse.ofText("limit должен быть от 1 до 100.");
    }

    Integer offset = parseInt(opts.get("offset"));
    if (offset == null) offset = 0;
    if (offset < 0) {
      return ChatResponse.ofText("offset должен быть >= 0.");
    }

    Map<String, Object> resp =
        downstream.marketInstruments(
            token, engine, market, board, filter, limit, offset, env.correlationId());
    List<Map<String, Object>> items = listOfMaps(resp.get("instruments"));
    if (items.isEmpty()) {
      return ChatResponse.ofText("Пусто.");
    }

    List<List<String>> rows = new ArrayList<>();
    for (Map<String, Object> item : items) {
      rows.add(
          List.of(
              s(item.get("secId")),
              s(item.get("shortName")),
              n(item.get("lastPrice")),
              n(item.get("prevPrice")),
              s(item.get("currency")),
              s(item.get("board"))));
    }
    String header =
        "Инструменты"
            + " (board="
            + board
            + ", limit="
            + limit
            + ", offset="
            + offset
            + (filter == null ? "" : ", filter=" + filter)
            + ")";
    String table = textTable.render(List.of("SEC", "NAME", "LAST", "PREV", "CUR", "BOARD"), rows);
    return ChatResponse.of(OutgoingMessage.pre(header + "\n" + table));
  }

  private ChatResponse marketQuote(
      ChatMessageEnvelope env,
      String token,
      Parsed p,
      Map<String, String> opts,
      String engine,
      String market,
      String board) {
    String sec = positional(p.arg2());
    if (sec == null) {
      sec = opts.get("sec");
    }
    if (sec == null) {
      return ChatResponse.ofText("Использование: /market_quote <SEC> [board=TQBR]");
    }

    Map<String, Object> resp =
        downstream.marketQuote(token, engine, market, board, sec, env.correlationId());
    Map<String, Object> quote = mapOf(resp.get("quote"));
    if (quote.isEmpty()) {
      return ChatResponse.ofText("Нет данных по тикеру " + sec + ".");
    }

    List<List<String>> rows =
        List.of(
            List.of(
                s(quote.get("secId")),
                n(quote.get("lastPrice")),
                n(quote.get("change")),
                n(quote.get("changePercent")),
                n(quote.get("volume")),
                s(quote.get("time"))));
    String header = "Котировка " + sec + " (board=" + board + ")";
    String table = textTable.render(List.of("SEC", "LAST", "CHG", "CHG%", "VOL", "TIME"), rows);
    return ChatResponse.of(OutgoingMessage.pre(header + "\n" + table));
  }

  private ChatResponse marketCandles(
      ChatMessageEnvelope env,
      String token,
      Parsed p,
      Map<String, String> opts,
      String engine,
      String market,
      String board) {
    String sec = positional(p.arg2());
    if (sec == null) {
      sec = opts.get("sec");
    }
    if (sec == null) {
      return ChatResponse.ofText(
          "Использование: /market_candles <SEC> [interval=60 from=YYYY-MM-DD till=YYYY-MM-DD limit=10 board=TQBR]");
    }

    Integer interval = parseInt(opts.get("interval"));
    if (interval == null) interval = 60;
    if (!(interval == 1 || interval == 10 || interval == 60 || interval == 1440)) {
      return ChatResponse.ofText("interval должен быть одним из: 1, 10, 60, 1440.");
    }

    Integer limit = parseInt(opts.get("limit"));
    if (limit == null) limit = 10;
    if (limit < 1 || limit > 100) {
      return ChatResponse.ofText("limit должен быть от 1 до 100.");
    }

    String from = opts.get("from");
    String till = opts.get("till");

    Map<String, Object> resp =
        downstream.marketCandles(
            token, engine, market, board, sec, interval, from, till, env.correlationId());
    List<Map<String, Object>> candles = listOfMaps(resp.get("candles"));
    if (candles.isEmpty()) {
      return ChatResponse.ofText("Пусто.");
    }

    int total = candles.size();
    int fromIdx = Math.max(0, total - limit);
    List<Map<String, Object>> slice = candles.subList(fromIdx, total);

    List<List<String>> rows = new ArrayList<>();
    for (Map<String, Object> candle : slice) {
      rows.add(
          List.of(
              s(candle.get("begin")),
              n(candle.get("open")),
              n(candle.get("high")),
              n(candle.get("low")),
              n(candle.get("close")),
              n(candle.get("volume"))));
    }
    String header =
        "Свечи " + sec + " (interval=" + interval + ", shown=" + slice.size() + "/" + total + ")";
    String table = textTable.render(List.of("BEGIN", "OPEN", "HIGH", "LOW", "CLOSE", "VOL"), rows);
    return ChatResponse.of(OutgoingMessage.pre(header + "\n" + table));
  }

  private ChatResponse marketOrderBook(
      ChatMessageEnvelope env,
      String token,
      Parsed p,
      Map<String, String> opts,
      String engine,
      String market,
      String board) {
    String sec = positional(p.arg2());
    if (sec == null) {
      sec = opts.get("sec");
    }
    if (sec == null) {
      return ChatResponse.ofText("Использование: /market_orderbook <SEC> [depth=10 board=TQBR]");
    }

    Integer depth = parseInt(opts.get("depth"));
    if (depth == null) depth = 10;
    if (depth < 1 || depth > 50) {
      return ChatResponse.ofText("depth должен быть от 1 до 50.");
    }

    Map<String, Object> resp =
        downstream.marketOrderBook(token, engine, market, board, sec, depth, env.correlationId());
    Map<String, Object> orderBook = mapOf(resp.get("orderBook"));
    List<Map<String, Object>> bids = listOfMaps(orderBook.get("bids"));
    List<Map<String, Object>> asks = listOfMaps(orderBook.get("asks"));
    if (bids.isEmpty() && asks.isEmpty()) {
      return ChatResponse.ofText("Пусто.");
    }

    StringBuilder sb = new StringBuilder();
    sb.append("Стакан ").append(sec).append(" (depth=").append(depth).append(")\n");

    if (!bids.isEmpty()) {
      sb.append("\nBids:\n");
      sb.append(
          textTable.render(
              List.of("PRICE", "QTY"), rowsFromEntries(bids, List.of("price", "quantity"))));
    }
    if (!asks.isEmpty()) {
      sb.append("\n\nAsks:\n");
      sb.append(
          textTable.render(
              List.of("PRICE", "QTY"), rowsFromEntries(asks, List.of("price", "quantity"))));
    }

    return ChatResponse.of(OutgoingMessage.pre(sb.toString().trim()));
  }

  private ChatResponse marketTrades(
      ChatMessageEnvelope env,
      String token,
      Parsed p,
      Map<String, String> opts,
      String engine,
      String market,
      String board) {
    String sec = positional(p.arg2());
    if (sec == null) {
      sec = opts.get("sec");
    }
    if (sec == null) {
      return ChatResponse.ofText(
          "Использование: /market_trades <SEC> [limit=10 from=<id|ISO> board=TQBR]");
    }

    Integer limit = parseInt(opts.get("limit"));
    if (limit == null) limit = 10;
    if (!(limit == 1 || limit == 10 || limit == 100 || limit == 1000 || limit == 5000)) {
      return ChatResponse.ofText("limit должен быть одним из: 1, 10, 100, 1000, 5000.");
    }

    String from = opts.get("from");

    Map<String, Object> resp =
        downstream.marketTrades(
            token, engine, market, board, sec, from, limit, env.correlationId());
    List<Map<String, Object>> trades = listOfMaps(resp.get("trades"));
    if (trades.isEmpty()) {
      return ChatResponse.ofText("Пусто.");
    }

    List<List<String>> rows = new ArrayList<>();
    for (Map<String, Object> trade : trades) {
      rows.add(
          List.of(
              s(trade.get("tradeNo")),
              s(trade.get("time")),
              n(trade.get("price")),
              n(trade.get("quantity")),
              s(trade.get("side"))));
    }
    String header =
        "Сделки " + sec + " (limit=" + limit + (from == null ? "" : ", from=" + from) + ")";
    String table = textTable.render(List.of("NO", "TIME", "PRICE", "QTY", "SIDE"), rows);
    return ChatResponse.of(OutgoingMessage.pre(header + "\n" + table));
  }

  /* =========================
  Admin console commands
  ========================= */

  private ChatResponse doAdminLogin(ChatMessageEnvelope env, Parsed p) {
    if (p.arg1() == null || p.arg1().isBlank()) {
      return ChatResponse.ofText("Использование: adminlogin <code>");
    }

    if (!adminCodeLimiter.tryConsume(rateKey(env))) {
      return ChatResponse.ofText("Слишком много попыток. Подожди немного и попробуй снова.");
    }

    // must be linked, otherwise gateway cannot elevate
    try {
      var r = gateway.resolve(providerCode(env), env.externalUserId());
      if (!r.linked()) {
        return ChatResponse.ofText("Сначала привяжи аккаунт: /login");
      }

      var res = gateway.elevateByCode(providerCode(env), env.externalUserId(), p.arg1());
      if (res == null || !res.ok()) {
        return ChatResponse.ofText("Не удалось выполнить adminlogin.");
      }
      return ChatResponse.ofText(
          "Ок. Роль ADMIN выдана.\nlogin=" + safe(res.login()) + "\nroles=" + res.roles());
    } catch (RestClientResponseException e) {
      return ChatResponse.ofText(formatError(canSeeRaw(env), "adminlogin", e));
    }
  }

  private ChatResponse doAdminUser(ChatMessageEnvelope env, Parsed p) {
    if (p.arg1() == null || p.arg1().isBlank()) {
      return ChatResponse.ofText("Использование: user <login>");
    }
    try {
      var r = gateway.resolve(providerCode(env), env.externalUserId());
      if (!r.linked()) {
        return ChatResponse.ofText("Сначала привяжи аккаунт: /login");
      }
      if (!r.perms().contains(PERM_ADMIN_MANAGE)) {
        return ChatResponse.ofText("Нет прав для операции.");
      }
      boolean canRaw = r.perms().contains(PERM_ADMIN_ANSWERS_LOG);

      var u = gateway.getUser(r.userId(), p.arg1());
      if (u == null) {
        return ChatResponse.ofText("Пользователь не найден.");
      }
      return ChatResponse.ofText(formatUserInfo(canRaw, u));
    } catch (RestClientResponseException e) {
      return ChatResponse.ofText(formatError(canSeeRaw(env), "user", e));
    }
  }

  private ChatResponse doAdminUsers(ChatMessageEnvelope env) {
    try {
      var r = gateway.resolve(providerCode(env), env.externalUserId());
      if (!r.linked()) {
        return ChatResponse.ofText("Сначала привяжи аккаунт: /login");
      }
      if (!r.perms().contains(PERM_ADMIN_MANAGE)) {
        return ChatResponse.ofText("Нет прав для операции.");
      }
      var list = gateway.listUsers(r.userId());
      if (list == null || list.users() == null || list.users().isEmpty()) {
        return ChatResponse.ofText("Пусто.");
      }

      StringBuilder sb = new StringBuilder("Пользователи:\n");
      for (var u : list.users()) {
        sb.append("- ").append(u.userId()).append(": ").append(safe(u.login())).append("\n");
      }
      return ChatResponse.ofText(sb.toString().trim());
    } catch (RestClientResponseException e) {
      return ChatResponse.ofText(formatError(canSeeRaw(env), "users", e));
    }
  }

  private ChatResponse doAdminRoles(ChatMessageEnvelope env) {
    try {
      var r = gateway.resolve(providerCode(env), env.externalUserId());
      if (!r.linked()) {
        return ChatResponse.ofText("Сначала привяжи аккаунт: /login");
      }
      if (!r.perms().contains(PERM_ADMIN_MANAGE)) {
        return ChatResponse.ofText("Нет прав для операции.");
      }
      var roles = gateway.listRoles(r.userId());
      if (roles == null || roles.roles() == null || roles.roles().isEmpty()) {
        return ChatResponse.ofText("Пусто.");
      }
      StringBuilder sb = new StringBuilder("Роли:\n");
      for (var role : roles.roles()) {
        sb.append("- ").append(role.code()).append(" - ").append(safe(role.name())).append("\n");
      }
      return ChatResponse.ofText(sb.toString().trim());
    } catch (RestClientResponseException e) {
      return ChatResponse.ofText(formatError(canSeeRaw(env), "roles", e));
    }
  }

  private ChatResponse doAdminPerms(ChatMessageEnvelope env) {
    try {
      var r = gateway.resolve(providerCode(env), env.externalUserId());
      if (!r.linked()) {
        return ChatResponse.ofText("Сначала привяжи аккаунт: /login");
      }
      if (!r.perms().contains(PERM_ADMIN_MANAGE)) {
        return ChatResponse.ofText("Нет прав для операции.");
      }
      var perms = gateway.listPerms(r.userId());
      if (perms == null || perms.perms() == null || perms.perms().isEmpty()) {
        return ChatResponse.ofText("Пусто.");
      }
      StringBuilder sb = new StringBuilder("Права:\n");
      for (var perm : perms.perms()) {
        sb.append("- ").append(perm.code()).append(" - ").append(safe(perm.name())).append("\n");
      }
      return ChatResponse.ofText(sb.toString().trim());
    } catch (RestClientResponseException e) {
      return ChatResponse.ofText(formatError(canSeeRaw(env), "perms", e));
    }
  }

  private ChatResponse doAdminGrantRole(ChatMessageEnvelope env, Parsed p) {
    if (p.arg1() == null || p.arg2() == null) {
      return ChatResponse.ofText("Использование: grantrole <login> <roleCode>");
    }
    return doRoleMutation(env, p.arg1(), p.arg2(), true);
  }

  private ChatResponse doAdminRevokeRole(ChatMessageEnvelope env, Parsed p) {
    if (p.arg1() == null || p.arg2() == null) {
      return ChatResponse.ofText("Использование: revokerole <login> <roleCode>");
    }
    return doRoleMutation(env, p.arg1(), p.arg2(), false);
  }

  private ChatResponse doRoleMutation(
      ChatMessageEnvelope env, String targetLogin, String roleCode, boolean grant) {
    try {
      var r = gateway.resolve(providerCode(env), env.externalUserId());
      if (!r.linked()) {
        return ChatResponse.ofText("Сначала привяжи аккаунт: /login");
      }
      if (!r.perms().contains(PERM_ADMIN_MANAGE)) {
        return ChatResponse.ofText("Нет прав для операции.");
      }
      boolean canRaw = r.perms().contains(PERM_ADMIN_ANSWERS_LOG);

      var u =
          grant
              ? gateway.grantRole(r.userId(), targetLogin, roleCode)
              : gateway.revokeRole(r.userId(), targetLogin, roleCode);

      return ChatResponse.ofText(
          (grant ? "Ок. Роль выдана." : "Ок. Роль отозвана.") + "\n" + formatUserInfo(canRaw, u));
    } catch (RestClientResponseException e) {
      return ChatResponse.ofText(
          formatError(canSeeRaw(env), grant ? "grantrole" : "revokerole", e));
    }
  }

  private ChatResponse doAdminGrantPerm(ChatMessageEnvelope env, Parsed p, boolean allow) {
    if (p.arg1() == null || p.arg2() == null) {
      return ChatResponse.ofText(
          "Использование: " + (allow ? "grantperm" : "denyperm") + " <login> <permCode> [reason]");
    }
    String reason = p.arg3();

    try {
      var r = gateway.resolve(providerCode(env), env.externalUserId());
      if (!r.linked()) {
        return ChatResponse.ofText("Сначала привяжи аккаунт: /login");
      }
      if (!r.perms().contains(PERM_ADMIN_MANAGE)) {
        return ChatResponse.ofText("Нет прав для операции.");
      }
      boolean canRaw = r.perms().contains(PERM_ADMIN_ANSWERS_LOG);

      var u =
          allow
              ? gateway.grantPerm(r.userId(), p.arg1(), p.arg2(), reason)
              : gateway.denyPerm(r.userId(), p.arg1(), p.arg2(), reason);

      return ChatResponse.ofText(
          (allow ? "Ок. Override allow задан." : "Ок. Override deny задан.")
              + "\n"
              + formatUserInfo(canRaw, u));
    } catch (RestClientResponseException e) {
      return ChatResponse.ofText(formatError(canSeeRaw(env), allow ? "grantperm" : "denyperm", e));
    }
  }

  private ChatResponse doAdminRevokePerm(ChatMessageEnvelope env, Parsed p) {
    if (p.arg1() == null || p.arg2() == null) {
      return ChatResponse.ofText("Использование: revokeperm <login> <permCode>");
    }

    try {
      var r = gateway.resolve(providerCode(env), env.externalUserId());
      if (!r.linked()) {
        return ChatResponse.ofText("Сначала привяжи аккаунт: /login");
      }
      if (!r.perms().contains(PERM_ADMIN_MANAGE)) {
        return ChatResponse.ofText("Нет прав для операции.");
      }
      boolean canRaw = r.perms().contains(PERM_ADMIN_ANSWERS_LOG);

      var u = gateway.revokePerm(r.userId(), p.arg1(), p.arg2());

      return ChatResponse.ofText("Ок. Override удалён.\n" + formatUserInfo(canRaw, u));
    } catch (RestClientResponseException e) {
      return ChatResponse.ofText(formatError(canSeeRaw(env), "revokeperm", e));
    }
  }

  /* =========================
  Formatting / parsing
  ========================= */

  private static String marketHelpText() {
    return String.join(
        "\n",
        "Market команды:",
        "/market_instruments [filter] [limit=10 offset=0 board=TQBR engine=stock market=shares]",
        "/market_quote <SEC> [board=TQBR]",
        "/market_candles <SEC> [interval=60 from=YYYY-MM-DD till=YYYY-MM-DD limit=10 board=TQBR]",
        "/market_orderbook <SEC> [depth=10 board=TQBR]",
        "/market_trades <SEC> [limit=10 from=<id|ISO> board=TQBR]",
        "",
        "Примеры:",
        "/market_instruments *Id* limit=20",
        "/market_quote *Id*",
        "/market_candles *Id* interval=60 from=2024-01-01 till=2024-01-31",
        "/market_orderbook *Id* depth=5",
        "/market_trades *Id* limit=100");
  }

  private boolean isDevAdmin(GatewayInternalClient.ResolveResponse res) {
    if (!devConsoleEnabled || res == null || !res.linked() || res.perms() == null) {
      return false;
    }
    if (res.perms().contains(PERM_DEVGOD)) {
      return true;
    }
    return hasAnyPerm(
        res.perms(), Set.of(PERM_ADMIN_MANAGE, PERM_COMMANDS_TOGGLE, PERM_USERS_HARD_DELETE));
  }

  private boolean canRevealAccountDetails(GatewayInternalClient.ResolveResponse res) {
    if (res == null || !res.linked() || res.perms() == null) {
      return false;
    }
    if (res.perms().contains(PERM_DEVGOD)) {
      return true;
    }
    return hasAnyPerm(
        res.perms(), Set.of(PERM_ADMIN_MANAGE, PERM_COMMANDS_TOGGLE, PERM_USERS_HARD_DELETE));
  }

  private DbPlan resolveDbPlan(String input) {
    String rest = extractDbSql(input);
    if (rest == null || rest.isBlank()) {
      return new DbPlan(null, DB_DEFAULT_MAX_ROWS, null, "Нужен SQL.");
    }
    String trimmed = rest.trim();
    String[] parts = trimmed.split("\\s+", 2);
    String cmd = parts[0].toLowerCase(Locale.ROOT);
    String arg = parts.length > 1 ? parts[1].trim() : null;
    return switch (cmd) {
      case "tables" -> new DbPlan(DB_TABLES_SQL, DB_TABLES_MAX_ROWS, "Таблицы", null);
      case "history", "flyway" -> new DbPlan(DB_HISTORY_SQL, DB_TABLES_MAX_ROWS, "Flyway", null);
      case "describe", "desc" -> {
        if (arg == null || arg.isBlank()) {
          yield new DbPlan(
              null, DB_DEFAULT_MAX_ROWS, null, "Нужна таблица. Пример: /db_describe public.users");
        }
        String schema = "public";
        String table = arg;
        int dot = arg.indexOf('.');
        if (dot > 0 && dot < arg.length() - 1) {
          schema = arg.substring(0, dot);
          table = arg.substring(dot + 1);
        }
        if (table.isBlank()) {
          yield new DbPlan(
              null, DB_DEFAULT_MAX_ROWS, null, "Нужна таблица. Пример: /db_describe public.users");
        }
        String sql =
            "select column_name, data_type, is_nullable, column_default "
                + "from information_schema.columns "
                + "where table_schema='"
                + escapeSqlLiteral(schema)
                + "' and table_name='"
                + escapeSqlLiteral(table)
                + "' order by ordinal_position";
        yield new DbPlan(sql, DB_TABLES_MAX_ROWS, "Структура " + schema + "." + table, null);
      }
      default -> new DbPlan(trimmed, DB_DEFAULT_MAX_ROWS, "SQL", null);
    };
  }

  private static String extractDbSql(String input) {
    if (input == null || input.isBlank()) {
      return null;
    }
    String trimmed = input.trim();
    if (!trimmed.startsWith("/db")) {
      return null;
    }
    String rest = trimmed.substring("/db".length());
    return rest == null ? null : rest.trim();
  }

  private static String safeDbError(String error) {
    if (error == null || error.isBlank()) {
      return "Проверь запрос.";
    }
    String text = error.replace("\n", " ").replace("\r", " ").trim();
    if (text.length() > 240) {
      text = text.substring(0, 240) + "...";
    }
    return text;
  }

  private static String escapeSqlLiteral(String value) {
    if (value == null) {
      return "";
    }
    return value.replace("'", "''").trim();
  }

  private static Map<String, String> parseOptions(String... parts) {
    Map<String, String> out = new HashMap<>();
    if (parts == null) return out;
    for (String part : parts) {
      if (part == null || part.isBlank()) continue;
      for (String token : part.trim().split("\\s+")) {
        if (token.isBlank()) continue;
        int pos = token.indexOf('=');
        if (pos <= 0 || pos >= token.length() - 1) continue;
        String key = token.substring(0, pos).toLowerCase(Locale.ROOT);
        String value = token.substring(pos + 1);
        out.put(key, value);
      }
    }
    return out;
  }

  private static String opt(Map<String, String> opts, String key, String def) {
    if (opts == null) return def;
    String value = opts.get(key);
    if (value == null || value.isBlank()) return def;
    return value;
  }

  private static String positional(String arg) {
    if (arg == null || arg.isBlank()) return null;
    if (arg.contains("=")) return null;
    return arg;
  }

  private static Integer parseInt(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> listOfMaps(Object value) {
    if (!(value instanceof List<?> list)) return List.of();
    List<Map<String, Object>> out = new ArrayList<>();
    for (Object item : list) {
      if (item instanceof Map<?, ?> map) {
        out.add((Map<String, Object>) map);
      }
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> mapOf(Object value) {
    if (value instanceof Map<?, ?> map) {
      return (Map<String, Object>) map;
    }
    return Map.of();
  }

  private static List<List<String>> rowsFromEntries(
      List<Map<String, Object>> entries, List<String> fields) {
    List<List<String>> rows = new ArrayList<>();
    for (Map<String, Object> entry : entries) {
      List<String> row = new ArrayList<>();
      for (String field : fields) {
        row.add(n(entry.get(field)));
      }
      rows.add(row);
    }
    return rows;
  }

  private static String s(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static String n(Object value) {
    if (value == null) return "";
    if (value instanceof BigDecimal decimal) {
      return formatDecimal(decimal);
    }
    if (value instanceof Number number) {
      try {
        return formatDecimal(new BigDecimal(number.toString()));
      } catch (NumberFormatException e) {
        return number.toString();
      }
    }
    return String.valueOf(value);
  }

  private static String formatDecimal(BigDecimal value) {
    BigDecimal stripped = value.stripTrailingZeros();
    if (stripped.scale() < 0) {
      stripped = stripped.setScale(0);
    }
    return stripped.toPlainString();
  }

  private static boolean looksLikeCredentials(String text) {
    return text != null && text.trim().split("\\s+").length >= 2;
  }

  private boolean isCancel(String text) {
    Parsed p = parse(text);
    return "/cancel".equals(p.cmd());
  }

  private Parsed parse(String text) {
    String normalized = text.startsWith("/") ? text : "/" + text;
    String[] parts = normalized.trim().split("\\s+", 4);

    String rawCmd = parts[0].toLowerCase(Locale.ROOT);
    String cmd = aliases.getOrDefault(rawCmd, rawCmd);

    String arg1 = parts.length >= 2 ? parts[1] : null;
    String arg2 = parts.length >= 3 ? parts[2] : null;
    String arg3 = parts.length >= 4 ? parts[3] : null;

    return new Parsed(cmd, arg1, arg2, arg3);
  }

  private static Map<String, String> buildAliases() {
    Map<String, String> m = new HashMap<>();

    // base
    m.put("/start", "/start");
    m.put("/старт", "/start");
    m.put("/help", "/help");
    m.put("/помощь", "/help");
    m.put("/хелп", "/help");
    m.put("/команды", "/help");

    // dev help
    m.put("/helpdev", "/helpdev");

    // login/register aliases
    m.put("/login", "/login");
    m.put("/логин", "/login");
    m.put("/вход", "/login");

    m.put("/register", "/register");
    m.put("/регистрация", "/register");
    m.put("/зарегистрироваться", "/register");

    // logout
    m.put("/logout", "/logout");
    m.put("/логаут", "/logout");
    m.put("/выход", "/logout");

    // me
    m.put("/me", "/me");
    m.put("/я", "/me");
    m.put("/профиль", "/me");

    // db
    m.put("/db", "/db");
    m.put("/db_menu", "/db_menu");

    // demo/protected calls
    m.put("/market", "/market");
    m.put("/рынок", "/market");
    m.put("/alerts", "/alerts");
    m.put("/алерты", "/alerts");
    m.put("/уведомления", "/alerts");
    m.put("/broker", "/broker");
    m.put("/брокер", "/broker");
    m.put("/trade", "/trade");
    m.put("/торг", "/trade");
    m.put("/сделка", "/trade");

    // cancel
    m.put("/cancel", "/cancel");
    m.put("/отмена", "/cancel");
    m.put("/стоп", "/cancel");

    // admin login (dev)
    m.put("/adminlogin", "/adminlogin");
    m.put("/админлогин", "/adminlogin");

    // admin console commands (English + RU aliases)
    m.put("/user", "/user");
    m.put("/пользователь", "/user");
    m.put("/users", "/users");
    m.put("/пользователи", "/users");
    m.put("/roles", "/roles");
    m.put("/роли", "/roles");
    m.put("/perms", "/perms");
    m.put("/права", "/perms");
    m.put("/grantrole", "/grantrole");
    m.put("/выдатьроль", "/grantrole");
    m.put("/revokerole", "/revokerole");
    m.put("/отозватьроль", "/revokerole");
    m.put("/grantperm", "/grantperm");
    m.put("/разрешитьправо", "/grantperm");
    m.put("/denyperm", "/denyperm");
    m.put("/запретитьправо", "/denyperm");
    m.put("/revokeperm", "/revokeperm");
    m.put("/снятьправо", "/revokeperm");

    // command switches
    m.put("/commands", "/commands");
    m.put("/command", "/command");

    return m;
  }

  private static String stateKey(ChatMessageEnvelope env) {
    String sessionId = env.sessionId();
    if (sessionId != null && !sessionId.isBlank()) {
      return sessionId;
    }
    return env.channel() + "|" + env.externalUserId() + "|" + env.chatId();
  }

  private static String providerCode(ChatMessageEnvelope env) {
    String ch = env.channel() == null ? "" : env.channel().trim();
    if (ch.isBlank()) return "TELEGRAM"; // default for dev mode
    return ch.toUpperCase(Locale.ROOT);
  }

  private static String rateKey(ChatMessageEnvelope env) {
    return providerCode(env) + "|" + env.externalUserId();
  }

  private String extractInput(ChatMessageEnvelope env) {
    String callback = env.callbackData();
    if (callback != null && !callback.isBlank()) {
      return callback.trim();
    }
    String text = env.text();
    return text == null ? "" : text.trim();
  }

  private static String normalizeInput(String input) {
    String trimmed = input == null ? "" : input.trim();
    if (!trimmed.startsWith("cmd:")) {
      return trimmed;
    }
    String rest = trimmed.substring("cmd:".length());
    String[] parts = rest.split(":");
    if (parts.length == 0) {
      return trimmed;
    }
    String cmd = parts[0];
    if (cmd.startsWith("/")) {
      cmd = cmd.substring(1);
    }
    StringBuilder sb = new StringBuilder("/").append(cmd);
    for (int i = 1; i < parts.length; i++) {
      sb.append(" ").append(parts[i]);
    }
    return sb.toString();
  }

  private static String expandUnderscoreCommands(String input) {
    if (input == null || input.isBlank()) {
      return input;
    }
    String trimmed = input.trim();
    if (trimmed.startsWith("/db_menu")) {
      return trimmed;
    }
    if (trimmed.startsWith("/db_")) {
      int space = trimmed.indexOf(' ');
      String cmd = space == -1 ? trimmed : trimmed.substring(0, space);
      String rest = space == -1 ? "" : trimmed.substring(space);
      String sub = cmd.substring("/db_".length());
      if (!sub.isBlank()) {
        return "/db " + sub + rest;
      }
    }
    if (trimmed.startsWith("/market_")) {
      int space = trimmed.indexOf(' ');
      String cmd = space == -1 ? trimmed : trimmed.substring(0, space);
      String rest = space == -1 ? "" : trimmed.substring(space);
      String sub = cmd.substring("/market_".length());
      if (!sub.isBlank()) {
        return "/market " + sub + rest;
      }
    }
    if (trimmed.startsWith("/user_delete")) {
      int space = trimmed.indexOf(' ');
      String rest = space == -1 ? "" : trimmed.substring(space);
      return "/user delete" + rest;
    }
    return trimmed;
  }

  /**
   * canSeeRaw(env) is a best-effort check:
   *
   * <p>We don't have userId without calling gateway, so the only safe way is: resolve -> perms
   * contains ADMIN_ANSWERS_LOG.
   */
  private boolean canSeeRaw(ChatMessageEnvelope env) {
    try {
      var res = gateway.resolve(providerCode(env), env.externalUserId());
      return res.linked() && res.perms().contains(PERM_ADMIN_ANSWERS_LOG);
    } catch (Exception ignore) {
      return false;
    }
  }

  private static boolean isPermitted(CommandDef def, Set<String> perms, boolean linked) {
    if ((!def.requiredAllPerms().isEmpty() || !def.requiredAnyPerms().isEmpty()) && !linked) {
      return false;
    }
    if (!def.requiredAllPerms().isEmpty() && !perms.containsAll(def.requiredAllPerms())) {
      return false;
    }
    if (!def.requiredAnyPerms().isEmpty()) {
      for (String p : def.requiredAnyPerms()) {
        if (perms.contains(p)) {
          return true;
        }
      }
      return false;
    }
    return true;
  }

  private static boolean hasAnyPerm(List<String> perms, Set<String> any) {
    if (perms == null || perms.isEmpty()) return false;
    for (String p : perms) {
      if (any.contains(p)) return true;
    }
    return false;
  }

  private static boolean hasAllPerm(List<String> perms, Set<String> all) {
    if (perms == null || perms.isEmpty()) return false;
    return perms.containsAll(all);
  }

  private InlineKeyboard buildCommandsPager(int page, int totalPages) {
    if (totalPages <= 1) {
      return null;
    }
    List<InlineKeyboard.Button> row = new ArrayList<>();
    if (page > 1) {
      row.add(new InlineKeyboard.Button("Назад", "cmd:commands:page=" + (page - 1)));
    }
    if (page < totalPages) {
      row.add(new InlineKeyboard.Button("Дальше", "cmd:commands:page=" + (page + 1)));
    }
    return row.isEmpty() ? null : new InlineKeyboard(List.of(row));
  }

  private InlineKeyboard buildMainMenuKeyboard() {
    return new InlineKeyboard(
        List.of(List.of(new InlineKeyboard.Button("Главное меню", "cmd:menu"))));
  }

  private static int parsePage(String arg) {
    if (arg == null || arg.isBlank()) return 1;
    String t = arg.trim();
    if (t.startsWith("page=")) {
      t = t.substring("page=".length());
    }
    try {
      return Integer.parseInt(t);
    } catch (NumberFormatException e) {
      return 1;
    }
  }

  private static String formatPermRequirement(CommandDef def) {
    if (!def.requiredAllPerms().isEmpty() && !def.requiredAnyPerms().isEmpty()) {
      return "ALL("
          + String.join(",", def.requiredAllPerms())
          + ")|ANY("
          + String.join(",", def.requiredAnyPerms())
          + ")";
    }
    if (!def.requiredAllPerms().isEmpty()) {
      return "ALL(" + String.join(",", def.requiredAllPerms()) + ")";
    }
    if (!def.requiredAnyPerms().isEmpty()) {
      return "ANY(" + String.join(",", def.requiredAnyPerms()) + ")";
    }
    return "-";
  }

  private static String formatCommandList(List<CommandDef> defs) {
    StringBuilder sb = new StringBuilder();
    for (CommandDef def : defs) {
      sb.append(def.command()).append(" - ").append(safe(def.description())).append("\n");
    }
    return sb.toString().trim();
  }

  private String formatCommandsPage(List<CommandDef> defs) {
    StringBuilder sb = new StringBuilder();
    for (CommandDef def : defs) {
      String enabled = def.toggleable() ? (switches.isEnabled(def.code()) ? "on" : "off") : "on";
      String toggle = def.toggleable() ? "toggle" : "fixed";
      String perm = formatPermRequirement(def);
      sb.append("- ")
          .append(def.code())
          .append(" [")
          .append(enabled)
          .append(", ")
          .append(toggle)
          .append(", perm=")
          .append(perm)
          .append("] - ")
          .append(safe(def.description()))
          .append("\n");
    }
    return sb.toString().trim();
  }

  private static Long parseLongOrNull(String s) {
    if (s == null) return null;
    String t = s.trim();
    if (t.isBlank()) return null;
    try {
      return Long.parseLong(t);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static String safe(String s) {
    return s == null ? "" : s;
  }

  private static String formatError(boolean raw, String op, RestClientResponseException e) {
    String friendly = friendlyMessage(op, e);
    if (!raw) {
      return friendly;
    }
    return friendly + "\nRAW: " + safeErr(e);
  }

  private static String friendlyMessage(String op, RestClientResponseException e) {
    int status = e.getRawStatusCode();
    return switch (status) {
      case 400 -> "Ошибка запроса (" + op + ").";
      case 401 -> "Не авторизовано / неверные данные (" + op + ").";
      case 403 -> "Нет прав для операции (" + op + ").";
      case 404 -> "Не найдено (" + op + ").";
      case 409 -> "Конфликт данных (" + op + ").";
      case 429 -> "Слишком много запросов (" + op + ").";
      default -> "Ошибка (" + op + "): HTTP " + status;
    };
  }

  private static String safeErr(RestClientResponseException e) {
    String body = e.getResponseBodyAsString();
    if (body == null) {
      return e.getStatusCode() + " " + e.getStatusText();
    }
    body = body.replaceAll("\\s+", " ").trim();
    if (body.length() > 400) {
      body = body.substring(0, 400) + "...";
    }
    return e.getStatusCode() + ": " + body;
  }

  private static String formatUserInfo(boolean raw, GatewayInternalClient.UserInfoResponse u) {
    if (u == null) return "<null>";

    StringBuilder sb = new StringBuilder();
    sb.append("userId=").append(u.userId()).append("\n");
    sb.append("login=").append(u.login()).append("\n");
    sb.append("roles=").append(u.roles() == null ? List.of() : u.roles()).append("\n");

    if (!raw) {
      // show only number of perms + overrides summary
      int permCount = u.perms() == null ? 0 : u.perms().size();
      int overridesCount = u.overrides() == null ? 0 : u.overrides().size();
      sb.append("perms_count=").append(permCount).append("\n");
      sb.append("overrides_count=").append(overridesCount);
      return sb.toString().trim();
    }

    sb.append("perms=").append(u.perms() == null ? List.of() : u.perms()).append("\n");
    sb.append("overrides=");
    if (u.overrides() == null || u.overrides().isEmpty()) {
      sb.append("[]");
    } else {
      sb.append("\n");
      for (var o : u.overrides()) {
        sb.append("- ").append(o.permCode()).append(" => ").append(o.allowed() ? "ALLOW" : "DENY");
        if (o.expiresAt() != null) sb.append(" exp=").append(o.expiresAt());
        if (o.reason() != null && !o.reason().isBlank()) sb.append(" reason=").append(o.reason());
        sb.append("\n");
      }
    }
    return sb.toString().trim();
  }

  private record Parsed(String cmd, String arg1, String arg2, String arg3) {}

  private record DbPlan(String sql, int maxRows, String title, String error) {}
}
