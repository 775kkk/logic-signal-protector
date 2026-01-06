package com.logicsignalprotector.commandcenter.domain;

import com.logicsignalprotector.commandcenter.api.dto.ChatMessageEnvelope;
import com.logicsignalprotector.commandcenter.api.dto.ChatResponse;
import com.logicsignalprotector.commandcenter.client.DownstreamClients;
import com.logicsignalprotector.commandcenter.client.GatewayInternalClient;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

/**
 * Step 1.3: core "brain" for chat commands.
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

  public ChatCommandHandler(
      GatewayInternalClient gateway, DownstreamClients downstream, ChatStateStore stateStore) {
    this.gateway = gateway;
    this.downstream = downstream;
    this.stateStore = stateStore;
  }

  public ChatResponse handle(ChatMessageEnvelope env) {
    String text = env.text() == null ? "" : env.text().trim();
    if (text.isBlank()) {
      return ChatResponse.ofText("Пустое сообщение.");
    }

    String key = stateKey(env);
    ChatState st = stateStore.get(key).orElse(ChatState.NONE);

    // If we're waiting for credentials, accept plain "login password" as the next input.
    if (st == ChatState.AWAIT_LOGIN_CREDENTIALS && !text.startsWith("/")) {
      return doLoginAndLink(env, key, text);
    }
    if (st == ChatState.AWAIT_REGISTER_CREDENTIALS && !text.startsWith("/")) {
      return doRegisterAndLink(env, key, text);
    }

    // Commands (leading slash) and also allow plain words like "help".
    String normalized = text.startsWith("/") ? text : "/" + text;
    String[] parts = normalized.split("\\s+", 3);
    String cmd = parts[0].toLowerCase();
    String arg1 = parts.length >= 2 ? parts[1] : null;
    String arg2 = parts.length >= 3 ? parts[2] : null;

    return switch (cmd) {
      case "/start", "/help" -> ChatResponse.ofText(helpText());
      case "/login" -> {
        if (arg1 == null || arg2 == null) {
          stateStore.set(key, ChatState.AWAIT_LOGIN_CREDENTIALS);
          yield ChatResponse.ofText("Логин: отправь сообщение в формате: <login> <password>");
        }
        stateStore.clear(key);
        yield doLoginAndLink(env, key, arg1 + " " + arg2);
      }
      case "/register" -> {
        if (arg1 == null || arg2 == null) {
          stateStore.set(key, ChatState.AWAIT_REGISTER_CREDENTIALS);
          yield ChatResponse.ofText("Регистрация: отправь сообщение в формате: <login> <password>");
        }
        stateStore.clear(key);
        yield doRegisterAndLink(env, key, arg1 + " " + arg2);
      }
      case "/me" -> doMe(env);
      case "/market" -> doProtectedCall(env, "market");
      case "/alerts" -> doProtectedCall(env, "alerts");
      case "/broker" -> doProtectedCall(env, "broker");
      case "/trade" -> doProtectedCall(env, "trade");
      default -> ChatResponse.ofText("Не понял команду. /help");
    };
  }

  private ChatResponse doMe(ChatMessageEnvelope env) {
    try {
      var res = gateway.resolve(providerCode(env), env.externalUserId());
      if (!res.linked()) {
        return ChatResponse.ofText("Аккаунт не привязан. Используй /login или /register.");
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
      return ChatResponse.ofText("Ошибка gateway: " + safeErr(e));
    }
  }

  private ChatResponse doLoginAndLink(ChatMessageEnvelope env, String key, String text) {
    String[] tokens = text.trim().split("\\s+", 2);
    if (tokens.length < 2) {
      stateStore.set(key, ChatState.AWAIT_LOGIN_CREDENTIALS);
      return ChatResponse.ofText("Нужно 2 значения: <login> <password>");
    }

    try {
      var t = gateway.loginAndLink(providerCode(env), env.externalUserId(), tokens[0], tokens[1]);
      stateStore.clear(key);
      return ChatResponse.ofText(
          "Ок. Привязка завершена.\nlogin=" + t.login() + "\nperms=" + t.perms());
    } catch (RestClientResponseException e) {
      stateStore.set(key, ChatState.AWAIT_LOGIN_CREDENTIALS);
      return ChatResponse.ofText("Ошибка логина: " + safeErr(e));
    }
  }

  private ChatResponse doRegisterAndLink(ChatMessageEnvelope env, String key, String text) {
    String[] tokens = text.trim().split("\\s+", 2);
    if (tokens.length < 2) {
      stateStore.set(key, ChatState.AWAIT_REGISTER_CREDENTIALS);
      return ChatResponse.ofText("Нужно 2 значения: <login> <password>");
    }

    try {
      var t =
          gateway.registerAndLink(providerCode(env), env.externalUserId(), tokens[0], tokens[1]);
      stateStore.clear(key);
      return ChatResponse.ofText(
          "Ок. Пользователь создан и привязан.\nlogin=" + t.login() + "\nperms=" + t.perms());
    } catch (RestClientResponseException e) {
      stateStore.set(key, ChatState.AWAIT_REGISTER_CREDENTIALS);
      return ChatResponse.ofText("Ошибка регистрации: " + safeErr(e));
    }
  }

  private ChatResponse doProtectedCall(ChatMessageEnvelope env, String kind) {
    try {
      var res = gateway.resolve(providerCode(env), env.externalUserId());
      if (!res.linked()) {
        return ChatResponse.ofText("Сначала привяжи аккаунт: /login или /register.");
      }
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

      return ChatResponse.ofText("Ответ " + kind + ":\n" + Objects.toString(out));
    } catch (RestClientResponseException e) {
      return ChatResponse.ofText("Ошибка: " + safeErr(e));
    } catch (Exception e) {
      return ChatResponse.ofText("Ошибка: " + e.getMessage());
    }
  }

  private static String stateKey(ChatMessageEnvelope env) {
    return env.channel() + "|" + env.externalUserId() + "|" + env.chatId();
  }

  private static String providerCode(ChatMessageEnvelope env) {
    String ch = env.channel() == null ? "" : env.channel().trim();
    if (ch.isBlank()) return "TELEGRAM"; // default for dev mode
    return ch.toUpperCase(Locale.ROOT);
  }

  private static String helpText() {
    return String.join(
        "\n",
        "Доступные команды:",
        "/login [login password] - вход и привязка",
        "/register [login password] - регистрация и привязка",
        "/me - информация о привязке",
        "/market - demo: защищённая ручка market-data-service",
        "/alerts - demo: защищённая ручка alerts-service",
        "/broker - demo: защищённая ручка broker-service",
        "/trade - demo: защищённая POST-ручка broker-service",
        "",
        "Подсказка:",
        "- Можно написать просто /login, а потом отдельным сообщением: login password",
        "- Парсинг команд в одном месте (logic-commands-center). (шаг 1.3)");
  }

  private static String safeErr(RestClientResponseException e) {
    String body = e.getResponseBodyAsString();
    if (body == null) {
      return e.getStatusCode() + " " + e.getStatusText();
    }
    body = body.replaceAll("\\s+", " ").trim();
    if (body.length() > 240) {
      body = body.substring(0, 240) + "...";
    }
    return e.getStatusCode() + ": " + body;
  }
}
