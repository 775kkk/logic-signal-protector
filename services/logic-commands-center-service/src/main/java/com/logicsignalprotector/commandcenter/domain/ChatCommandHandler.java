package com.logicsignalprotector.commandcenter.domain;

import com.logicsignalprotector.commandcenter.api.dto.ChatMessageEnvelope;
import com.logicsignalprotector.commandcenter.api.dto.ChatResponse;
import com.logicsignalprotector.commandcenter.client.DownstreamClients;
import com.logicsignalprotector.commandcenter.client.GatewayInternalClient;
import java.time.Duration;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

/**
 * Step 1.3-1.4: core "brain" for chat commands.
 *
 * <p>Input: raw user message in a universal envelope. Output: a list of texts to send back to the
 * user.
 */
@Service
@Slf4j
public class ChatCommandHandler {

  private final GatewayInternalClient gateway;
  private final DownstreamClients downstream;
  private final ChatStateStore stateStore;
  private final AdminCodeRateLimiter adminCodeLimiter;
  private final Duration logoutConfirmTtl;

  private static final String PERM_ADMIN_ANSWERS_LOG = "ADMIN_ANSWERS_LOG";

  private final Map<String, String> aliases = buildAliases();

  public ChatCommandHandler(
      GatewayInternalClient gateway,
      DownstreamClients downstream,
      ChatStateStore stateStore,
      AdminCodeRateLimiter adminCodeLimiter,
      @Value("${chat.logout.confirm-ttl:PT60S}") Duration logoutConfirmTtl) {
    this.gateway = gateway;
    this.downstream = downstream;
    this.stateStore = stateStore;
    this.adminCodeLimiter = adminCodeLimiter;
    this.logoutConfirmTtl = logoutConfirmTtl;
  }

  public ChatResponse handle(ChatMessageEnvelope env) {
    String text = env.text() == null ? "" : env.text().trim();
    if (text.isBlank()) {
      return ChatResponse.ofText("Пустое сообщение.");
    }

    String key = stateKey(env);
    ChatState st = stateStore.get(key).orElse(ChatState.NONE);

    // cancel should work in any state
    if (isCancel(text)) {
      stateStore.clear(key);
      return ChatResponse.ofText("Ок, отменено.");
    }

    // special state: logout confirmation
    if (st == ChatState.AWAIT_LOGOUT_CONFIRM) {
      return handleLogoutConfirm(env, key, text);
    }

    // If we're waiting for credentials, accept plain "login password" as the next input.
    if (st == ChatState.AWAIT_LOGIN_CREDENTIALS && !text.startsWith("/")) {
      if (!looksLikeCredentials(text)) {
        return ChatResponse.ofText("Нужно 2 значения: <login> <password> (или /cancel)");
      }
      return doLoginAndLink(env, key, text);
    }
    if (st == ChatState.AWAIT_REGISTER_CREDENTIALS && !text.startsWith("/")) {
      if (!looksLikeCredentials(text)) {
        return ChatResponse.ofText("Нужно 2 значения: <login> <password> (или /cancel)");
      }
      return doRegisterAndLink(env, key, text);
    }

    Parsed p = parse(text);

    return switch (p.cmd()) {
      case "/start", "/help" -> ChatResponse.ofText(helpText());
      case "/login" -> handleLoginCommand(env, key, p);
      case "/register" -> handleRegisterCommand(env, key, p);
      case "/logout" -> handleLogoutRequest(env, key, p);
      case "/me" -> doMe(env);
      case "/market" -> doProtectedCall(env, "market");
      case "/alerts" -> doProtectedCall(env, "alerts");
      case "/broker" -> doProtectedCall(env, "broker");
      case "/trade" -> doProtectedCall(env, "trade");

      // Step 1.4 admin console commands
      case "/adminlogin" -> doAdminLogin(env, p);
      case "/user" -> doAdminUser(env, p);
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
            "- отправь: logout yes (или logout да)", // also works with /logout yes
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
    // (assumption) allow plain "yes" as convenience
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

  private ChatResponse doMe(ChatMessageEnvelope env) {
    try {
      var res = gateway.resolve(providerCode(env), env.externalUserId());
      if (!res.linked()) {
        return ChatResponse.ofText("Аккаунт не привязан. Используй /login или /register.");
      }

      boolean canRaw = res.perms().contains(PERM_ADMIN_ANSWERS_LOG);
      if (!canRaw) {
        return ChatResponse.ofText(
            "Привязано: login=" + res.login() + "\nroles=" + String.join(", ", res.roles()));
      }

      return ChatResponse.ofText(
          "Привязано: userId="
              + res.userId()
              + ", login="
              + res.login()
              + "\nroles="
              + res.roles()
              + "\nperms="
              + res.perms());
    } catch (RestClientResponseException e) {
      return ChatResponse.ofText(formatError(canSeeRaw(env), "me", e));
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
      if (!canRaw) {
        return ChatResponse.ofText(
            "Ок. Привязка завершена. login=" + safe(t == null ? null : t.login()));
      }
      return ChatResponse.ofText(
          "Ок. Привязка завершена.\nlogin=" + safe(t.login()) + "\nperms=" + t.perms());
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
      if (!canRaw) {
        return ChatResponse.ofText(
            "Ок. Пользователь создан и привязан. login=" + safe(t == null ? null : t.login()));
      }
      return ChatResponse.ofText(
          "Ок. Пользователь создан и привязан.\nlogin=" + safe(t.login()) + "\nperms=" + t.perms());
    } catch (RestClientResponseException e) {
      stateStore.set(key, ChatState.AWAIT_REGISTER_CREDENTIALS);
      return ChatResponse.ofText(formatError(canSeeRaw(env), "register", e));
    }
  }

  private ChatResponse doProtectedCall(ChatMessageEnvelope env, String kind) {
    try {
      var res = gateway.resolve(providerCode(env), env.externalUserId());
      if (!res.linked()) {
        return ChatResponse.ofText("Сначала привяжи аккаунт: /login или /register.");
      }

      boolean canRaw = res.perms().contains(PERM_ADMIN_ANSWERS_LOG);
      var tokens = gateway.issueAccess(providerCode(env), env.externalUserId());
      if (tokens == null || tokens.accessToken() == null || tokens.accessToken().isBlank()) {
        return ChatResponse.ofText("Не удалось получить access token.");
      }

      Map<String, Object> out;
      switch (kind) {
        case "market" -> out = downstream.marketSecureSample(tokens.accessToken());
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
      var list = gateway.listUsers(r.userId());
      if (list == null || list.users() == null) {
        return ChatResponse.ofText("Пусто.");
      }
      StringBuilder sb = new StringBuilder();
      sb.append("Users (" + list.users().size() + "):\n");
      for (var u : list.users()) {
        sb.append("- ").append(u.login()).append(" (id=").append(u.userId()).append(")\n");
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
      var roles = gateway.listRoles(r.userId());
      if (roles == null || roles.roles() == null) {
        return ChatResponse.ofText("Пусто.");
      }
      StringBuilder sb = new StringBuilder("Roles:\n");
      for (var role : roles.roles()) {
        sb.append("- ").append(role.code()).append(" : ").append(safe(role.name())).append("\n");
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
      var perms = gateway.listPerms(r.userId());
      if (perms == null || perms.perms() == null) {
        return ChatResponse.ofText("Пусто.");
      }
      StringBuilder sb = new StringBuilder("Perms:\n");
      for (var perm : perms.perms()) {
        sb.append("- ").append(perm.code()).append(" : ").append(safe(perm.name())).append("\n");
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
    m.put("/команды", "/help");

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

    return m;
  }

  private static String stateKey(ChatMessageEnvelope env) {
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

  private static String helpText() {
    return String.join(
        "\n",
        "Доступные команды:",
        "/start | start | старт - показать help",
        "/help | help | помощь - список команд",
        "/login | login | логин - вход и привязка",
        "/register | register | регистрация - регистрация и привязка",
        "/logout | logout | логаут - отвязка (с подтверждением)",
        "/me | me | я - информация о привязке",
        "/market | market | рынок - demo: защищённая ручка market-data-service",
        "/alerts | alerts | уведомления - demo: защищённая ручка alerts-service",
        "/broker | broker | брокер - demo: защищённая ручка broker-service",
        "/trade | trade | сделка - demo: защищённая POST-ручка broker-service",
        "/cancel | cancel | отмена - отменить текущий ввод",
        "",
        "(admin/dev) /adminlogin | adminlogin | админлогин <code> - выдать себе роль ADMIN (если включено)",
        "(admin) /users | users | пользователи - список пользователей",
        "(admin) /user | user | пользователь <login> - инфо по пользователю",
        "(admin) /roles | roles | роли - справочник ролей",
        "(admin) /perms | perms | права - справочник прав",
        "(admin) /grantrole | grantrole | выдатьроль <login> <roleCode>",
        "(admin) /revokerole | revokerole | отозватьроль <login> <roleCode>",
        "(admin) /grantperm | grantperm | разрешитьправо <login> <permCode> [reason]",
        "(admin) /denyperm | denyperm | запретитьправо <login> <permCode> [reason]",
        "(admin) /revokeperm | revokeperm | снятьправо <login> <permCode>",
        "",
        "Подсказка:",
        "- Можно писать команды без '/', например: login, логин, выход, пользователи",
        "- Для login/register можно сначала отправить команду, затем отдельным сообщением: <login> <password>",
        "- Для logout подтверждение: logout yes (или logout да)");
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
}
