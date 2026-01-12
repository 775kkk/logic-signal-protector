package com.logicsignalprotector.commandcenter.domain.v2;

import com.logicsignalprotector.commandcenter.api.dto.ChatMessageEnvelope;
import com.logicsignalprotector.commandcenter.api.dto.v2.ActionBlock;
import com.logicsignalprotector.commandcenter.api.dto.v2.ActionItem;
import com.logicsignalprotector.commandcenter.api.dto.v2.ChatResponseV2;
import com.logicsignalprotector.commandcenter.api.dto.v2.ErrorBlock;
import com.logicsignalprotector.commandcenter.api.dto.v2.NoticeBlock;
import com.logicsignalprotector.commandcenter.api.dto.v2.ResponseBlock;
import com.logicsignalprotector.commandcenter.api.dto.v2.Section;
import com.logicsignalprotector.commandcenter.api.dto.v2.SectionsBlock;
import com.logicsignalprotector.commandcenter.api.dto.v2.TableBlock;
import com.logicsignalprotector.commandcenter.api.dto.v2.UiHintsV2;
import com.logicsignalprotector.commandcenter.client.DownstreamClients;
import com.logicsignalprotector.commandcenter.client.GatewayInternalClient;
import com.logicsignalprotector.commandcenter.domain.ChatState;
import com.logicsignalprotector.commandcenter.domain.ChatStateStore;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

@Service
@Slf4j
public class ChatCommandHandlerV2 {

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
  private static final Set<String> ADMIN_PERMS =
      Set.of("ADMIN_USERS_PERMS_REVOKE", "COMMANDS_TOGGLE", "USERS_HARD_DELETE");

  private final GatewayInternalClient gateway;
  private final DownstreamClients downstream;
  private final HelpBuilder helpBuilder;
  private final MenuBuilder menuBuilder;
  private final ChatStateStore stateStore;
  private final boolean devConsoleEnabled;
  private final Map<String, String> aliases = buildAliases();

  public ChatCommandHandlerV2(
      GatewayInternalClient gateway,
      DownstreamClients downstream,
      HelpBuilder helpBuilder,
      MenuBuilder menuBuilder,
      ChatStateStore stateStore,
      @Value("${dev.console.enabled:false}") boolean devConsoleEnabled) {
    this.gateway = gateway;
    this.downstream = downstream;
    this.helpBuilder = helpBuilder;
    this.menuBuilder = menuBuilder;
    this.stateStore = stateStore;
    this.devConsoleEnabled = devConsoleEnabled;
  }

  public ChatResponseV2 handle(ChatMessageEnvelope env) {
    String input = extractInput(env);
    if (input.isBlank()) {
      return error("EMPTY", "Пустое сообщение.", "Пришли команду или /help.", env);
    }

    PageRequest pageRequest = parsePageRequest(input);
    if (pageRequest != null) {
      String sessionId = ensureSessionId(env, pageRequest.sessionId());
      return switch (pageRequest.kind()) {
        case "h" -> doHelp(env, sessionId);
        case "m" -> doMenu(env, sessionId);
        case "mi" -> doMarketInstrumentsPage(env, sessionId, pageRequest.page());
        default -> error("UNKNOWN_CALLBACK", "Неизвестное действие.", "Попробуй /menu.", env);
      };
    }

    String normalized = normalizeInput(input);
    normalized = expandUnderscoreCommands(normalized);
    Parsed p = parse(normalized);
    return switch (p.cmd()) {
      case "/start", "/help" -> doHelp(env, ensureSessionId(env, null));
      case "/menu" -> doMenu(env, ensureSessionId(env, null));
      case "/menu_market" -> doMenuMarket(env, ensureSessionId(env, null));
      case "/menu_account" -> doMenuAccount(env, ensureSessionId(env, null));
      case "/menu_dev" -> doMenuDev(env, ensureSessionId(env, null));
      case "/db_menu" -> doDbMenu(env, ensureSessionId(env, null));
      case "/db" -> doDb(env, ensureSessionId(env, null), normalized);
      case "/market" -> doMarket(env, p);
      default -> error("UNKNOWN_COMMAND", "Неизвестная команда.", "Попробуй /menu или /help.", env);
    };
  }

  private ChatResponseV2 doHelp(ChatMessageEnvelope env, String sessionId) {
    try {
      var res = gateway.resolve(providerCode(env), env.externalUserId());
      Set<String> perms =
          res != null && res.perms() != null ? new HashSet<>(res.perms()) : Set.of();
      boolean linked = res != null && res.linked();
      SectionsBlock sections = helpBuilder.build(perms, linked, devConsoleEnabled);
      return response(List.of(new NoticeBlock("help_intro"), sections), env, sessionId);
    } catch (RestClientResponseException e) {
      return error("HELP_FAILED", "Не удалось собрать /help.", "Попробуй позже.", env);
    }
  }

  private ChatResponseV2 doMenu(ChatMessageEnvelope env, String sessionId) {
    try {
      var res = gateway.resolve(providerCode(env), env.externalUserId());
      Set<String> perms =
          res != null && res.perms() != null ? new HashSet<>(res.perms()) : Set.of();
      boolean linked = res != null && res.linked();
      SectionsBlock sections = menuBuilder.build(perms, linked, devConsoleEnabled);
      List<ResponseBlock> blocks = new ArrayList<>();
      blocks.add(sections);
      ActionBlock moduleActions = buildModuleActions(linked, perms);
      if (moduleActions != null) {
        blocks.add(moduleActions);
      }
      return response(blocks, env, sessionId, menuHints());
    } catch (RestClientResponseException e) {
      return error("MENU_FAILED", "Не удалось собрать /menu.", "Попробуй позже.", env);
    }
  }

  private ChatResponseV2 doMenuMarket(ChatMessageEnvelope env, String sessionId) {
    try {
      var res = gateway.resolve(providerCode(env), env.externalUserId());
      Set<String> perms =
          res != null && res.perms() != null ? new HashSet<>(res.perms()) : Set.of();
      boolean linked = res != null && res.linked();
      if (!menuBuilder.canMarket(perms, linked)) {
        return error(
            "FORBIDDEN",
            "Модуль биржи недоступен.",
            "Привяжи аккаунт и выдай MARKETDATA_READ.",
            env);
      }
      MarketStatusInfo status = fetchMarketStatus(env, "stock", "shares", "TQBR", "SBER");
      SectionsBlock sections = buildMarketSections(status);
      List<ResponseBlock> blocks = new ArrayList<>();
      blocks.add(sections);
      ActionBlock quickActions = buildMarketActions(linked, perms);
      if (quickActions != null) {
        blocks.add(quickActions);
      }
      return response(blocks, env, sessionId, menuHints());
    } catch (RestClientResponseException e) {
      return error("MENU_FAILED", "Не удалось собрать /menu_market.", "Попробуй позже.", env);
    }
  }

  private ChatResponseV2 doMenuAccount(ChatMessageEnvelope env, String sessionId) {
    try {
      var res = gateway.resolve(providerCode(env), env.externalUserId());
      Set<String> perms =
          res != null && res.perms() != null ? new HashSet<>(res.perms()) : Set.of();
      boolean linked = res != null && res.linked();
      boolean revealDetails = canRevealAccountDetails(perms, linked);
      SectionsBlock sections = buildAccountSections(res, revealDetails);
      List<ResponseBlock> blocks = new ArrayList<>();
      blocks.add(sections);
      blocks.add(buildBackAction());
      return response(blocks, env, sessionId, menuHints());
    } catch (RestClientResponseException e) {
      return error("MENU_FAILED", "Не удалось собрать /menu_account.", "Попробуй позже.", env);
    }
  }

  private ChatResponseV2 doMenuDev(ChatMessageEnvelope env, String sessionId) {
    try {
      var res = gateway.resolve(providerCode(env), env.externalUserId());
      Set<String> perms =
          res != null && res.perms() != null ? new HashSet<>(res.perms()) : Set.of();
      boolean linked = res != null && res.linked();
      if (!menuBuilder.canDev(perms, linked, devConsoleEnabled)) {
        return error("FORBIDDEN", "Dev-меню недоступно.", "Проверь права доступа.", env);
      }
      SectionsBlock sections = menuBuilder.buildDevMenu(devConsoleEnabled);
      List<ResponseBlock> blocks = new ArrayList<>();
      blocks.add(sections);
      blocks.add(buildBackAction());
      return response(blocks, env, sessionId, menuHints());
    } catch (RestClientResponseException e) {
      return error("MENU_FAILED", "Не удалось собрать /menu_dev.", "Попробуй позже.", env);
    }
  }

  private ChatResponseV2 doDbMenu(ChatMessageEnvelope env, String sessionId) {
    try {
      var res = gateway.resolve(providerCode(env), env.externalUserId());
      Set<String> perms =
          res != null && res.perms() != null ? new HashSet<>(res.perms()) : Set.of();
      boolean linked = res != null && res.linked();
      if (!menuBuilder.canDev(perms, linked, devConsoleEnabled)) {
        return error("FORBIDDEN", "DB меню недоступно.", "Проверь dev-права.", env);
      }
      SectionsBlock sections = buildDbMenuSections();
      List<ResponseBlock> blocks = new ArrayList<>();
      blocks.add(sections);
      blocks.add(buildDbActions(false, false, true, false, 0, 0));
      return response(blocks, env, sessionId, menuHints());
    } catch (RestClientResponseException e) {
      return error("DB_MENU_FAILED", "Не удалось открыть /db_menu.", "Попробуй позже.", env);
    }
  }

  private ChatResponseV2 doDb(ChatMessageEnvelope env, String sessionId, String input) {
    try {
      var res = gateway.resolve(providerCode(env), env.externalUserId());
      Set<String> perms =
          res != null && res.perms() != null ? new HashSet<>(res.perms()) : Set.of();
      boolean linked = res != null && res.linked();
      if (!menuBuilder.canDev(perms, linked, devConsoleEnabled)) {
        return error("FORBIDDEN", "DB команда недоступна.", "Проверь dev-права.", env);
      }

      DbFormatAction formatAction = resolveDbFormatAction(input);
      if (formatAction != null) {
        return renderDbFromState(env, sessionId, formatAction);
      }

      DbPlan plan = resolveDbPlan(input);
      if (plan == null || plan.sql() == null || plan.sql().isBlank()) {
        return error("BAD_INPUT", "Нужен SQL.", "Открой /db_menu.", env);
      }

      var resp = gateway.dbQuery(plan.sql(), plan.maxRows());
      if (resp == null || !resp.ok()) {
        String hint = resp == null ? "Проверь запрос." : safeDbError(resp.error());
        return error("DB_FAILED", "Ошибка выполнения SQL.", hint, env);
      }
      boolean menuContext = isDbMenuCallback(env);
      storeDbState(sessionId, plan, menuContext, false, 0);
      return buildDbResponse(resp, plan, env, sessionId, menuContext, false, 0);
    } catch (RestClientResponseException e) {
      return error("DB_FAILED", "Ошибка выполнения SQL.", "Попробуй позже.", env);
    }
  }

  private ChatResponseV2 doMarket(ChatMessageEnvelope env, Parsed p) {
    String sub = p.arg1() == null ? "help" : p.arg1().toLowerCase(Locale.ROOT);
    if ("help".equals(sub) || "h".equals(sub)) {
      MarketStatusInfo status = resolveMarketStatusForHelp(env);
      SectionsBlock sections = buildMarketSections(status);
      List<ResponseBlock> blocks = new ArrayList<>();
      blocks.add(new NoticeBlock("market_help"));
      blocks.add(sections);
      return response(blocks, env, ensureSessionId(env, null));
    }

    String sessionId = ensureSessionId(env, null);
    try {
      var res = gateway.resolve(providerCode(env), env.externalUserId());
      if (res == null || !res.linked()) {
        return error(
            "NOT_LINKED", "Сначала привяжи аккаунт.", "Используй /login или /register.", env);
      }
      if (res.perms() == null || !res.perms().contains("MARKETDATA_READ")) {
        return error(
            "FORBIDDEN", "Нет прав для операции.", "Если цель доступ, нужен MARKETDATA_READ.", env);
      }

      var tokens = gateway.issueAccess(providerCode(env), env.externalUserId());
      if (tokens == null || tokens.accessToken() == null || tokens.accessToken().isBlank()) {
        return error("NO_TOKEN", "Не удалось получить токен доступа.", "Попробуй позже.", env);
      }

      Map<String, String> opts = parseOptions(p.arg2(), p.arg3());
      String engine = opt(opts, "engine", "stock");
      String market = opt(opts, "market", "shares");
      String board = opt(opts, "board", "TQBR");

      return switch (sub) {
        case "instruments" ->
            marketInstruments(env, sessionId, tokens.accessToken(), p, opts, engine, market, board);
        case "quote" ->
            marketQuote(env, sessionId, tokens.accessToken(), p, opts, engine, market, board);
        case "candles" ->
            marketCandles(env, sessionId, tokens.accessToken(), p, opts, engine, market, board);
        case "orderbook" ->
            marketOrderBook(env, sessionId, tokens.accessToken(), p, opts, engine, market, board);
        case "trades" ->
            marketTrades(env, sessionId, tokens.accessToken(), p, opts, engine, market, board);
        default ->
            response(
                List.of(
                    new ErrorBlock(
                        "UNKNOWN_SUBCOMMAND",
                        "Неизвестная подкоманда: " + sub,
                        "Открой /market_help.",
                        null)),
                env,
                sessionId);
      };
    } catch (RestClientResponseException e) {
      return error("MARKET_FAILED", "Ошибка получения данных.", "Попробуй позже.", env);
    } catch (Exception e) {
      return error("MARKET_FAILED", "Ошибка: " + e.getMessage(), "Попробуй позже.", env);
    }
  }

  private ChatResponseV2 doMarketInstrumentsPage(
      ChatMessageEnvelope env, String sessionId, int offset) {
    MarketParams params = loadMarketParams(sessionId);
    if (params == null) {
      return error(
          "SESSION_EXPIRED", "Сессия пагинации истекла.", "Повтори /market_instruments.", env);
    }

    try {
      var res = gateway.resolve(providerCode(env), env.externalUserId());
      if (res == null || !res.linked()) {
        return error(
            "NOT_LINKED", "Сначала привяжи аккаунт.", "Используй /login или /register.", env);
      }
      if (res.perms() == null || !res.perms().contains("MARKETDATA_READ")) {
        return error(
            "FORBIDDEN", "Нет прав для операции.", "Если цель доступ, нужен MARKETDATA_READ.", env);
      }

      var tokens = gateway.issueAccess(providerCode(env), env.externalUserId());
      if (tokens == null || tokens.accessToken() == null || tokens.accessToken().isBlank()) {
        return error("NO_TOKEN", "Не удалось получить токен доступа.", "Попробуй позже.", env);
      }

      Map<String, String> opts = new HashMap<>();
      opts.put("limit", String.valueOf(params.limit()));
      opts.put("offset", String.valueOf(Math.max(0, offset)));
      if (params.filter() != null && !params.filter().isBlank()) {
        opts.put("filter", params.filter());
      }
      Parsed parsed = new Parsed("/market", "instruments", params.filter(), null);
      return marketInstruments(
          env,
          sessionId,
          tokens.accessToken(),
          parsed,
          opts,
          params.engine(),
          params.market(),
          params.board());
    } catch (RestClientResponseException e) {
      return error("MARKET_FAILED", "Ошибка получения данных.", "Попробуй позже.", env);
    } catch (Exception e) {
      return error("MARKET_FAILED", "Ошибка: " + e.getMessage(), "Попробуй позже.", env);
    }
  }

  private ChatResponseV2 marketInstruments(
      ChatMessageEnvelope env,
      String sessionId,
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
      return error("BAD_LIMIT", "limit должен быть от 1 до 100.", "Проверь параметры.", env);
    }

    Integer offset = parseInt(opts.get("offset"));
    if (offset == null) offset = 0;
    if (offset < 0) {
      return error("BAD_OFFSET", "offset должен быть >= 0.", "Проверь параметры.", env);
    }

    MarketParams params = new MarketParams(limit, filter, board, engine, market);
    storeMarketParams(sessionId, params);
    Map<String, Object> resp =
        downstream.marketInstruments(
            token, engine, market, board, filter, limit, offset, env.correlationId());
    List<Map<String, Object>> items = listOfMaps(resp.get("instruments"));
    if (items.isEmpty()) {
      return response(List.of(new NoticeBlock("market_empty")), env, sessionId);
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

    List<ResponseBlock> blocks = new ArrayList<>();
    blocks.add(new TableBlock(List.of("SEC", "NAME", "LAST", "PREV", "CUR", "BOARD"), rows, null));

    ActionBlock pager = buildMarketPager(sessionId, limit, offset, items.size());
    if (pager != null) {
      blocks.add(pager);
    }
    return response(blocks, env, sessionId);
  }

  private ChatResponseV2 marketQuote(
      ChatMessageEnvelope env,
      String sessionId,
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
      return error("BAD_INPUT", "Нужен тикер.", "Пример: /market_quote *Id*", env);
    }

    Map<String, Object> resp =
        downstream.marketQuote(token, engine, market, board, sec, env.correlationId());
    Map<String, Object> quote = mapOf(resp.get("quote"));
    if (quote.isEmpty()) {
      return response(List.of(new NoticeBlock("market_empty")), env, sessionId);
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
    List<ResponseBlock> blocks = new ArrayList<>();
    blocks.add(new TableBlock(List.of("SEC", "LAST", "CHG", "CHG%", "VOL", "TIME"), rows, null));
    return response(blocks, env, sessionId);
  }

  private ChatResponseV2 marketCandles(
      ChatMessageEnvelope env,
      String sessionId,
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
      return error("BAD_INPUT", "Нужен тикер.", "Пример: /market_candles *Id* interval=60", env);
    }

    Integer interval = parseInt(opts.get("interval"));
    if (interval == null) interval = 60;
    if (!(interval == 1 || interval == 10 || interval == 60 || interval == 1440)) {
      return error(
          "BAD_INTERVAL", "interval должен быть 1, 10, 60 или 1440.", "Проверь параметры.", env);
    }

    Integer limit = parseInt(opts.get("limit"));
    if (limit == null) limit = 10;
    if (limit < 1 || limit > 100) {
      return error("BAD_LIMIT", "limit должен быть от 1 до 100.", "Проверь параметры.", env);
    }

    String from = opts.get("from");
    String till = opts.get("till");

    Map<String, Object> resp =
        downstream.marketCandles(
            token, engine, market, board, sec, interval, from, till, env.correlationId());
    List<Map<String, Object>> candles = listOfMaps(resp.get("candles"));
    if (candles.isEmpty()) {
      return response(List.of(new NoticeBlock("market_empty")), env, sessionId);
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

    List<ResponseBlock> blocks = new ArrayList<>();
    blocks.add(new TableBlock(List.of("BEGIN", "OPEN", "HIGH", "LOW", "CLOSE", "VOL"), rows, null));
    return response(blocks, env, sessionId);
  }

  private ChatResponseV2 marketOrderBook(
      ChatMessageEnvelope env,
      String sessionId,
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
      return error("BAD_INPUT", "Нужен тикер.", "Пример: /market_orderbook *Id* depth=10", env);
    }

    Integer depth = parseInt(opts.get("depth"));
    if (depth == null) depth = 10;
    if (depth < 1 || depth > 50) {
      return error("BAD_DEPTH", "depth должен быть от 1 до 50.", "Проверь параметры.", env);
    }

    Map<String, Object> resp =
        downstream.marketOrderBook(token, engine, market, board, sec, depth, env.correlationId());
    Map<String, Object> orderBook = mapOf(resp.get("orderBook"));
    List<Map<String, Object>> bids = listOfMaps(orderBook.get("bids"));
    List<Map<String, Object>> asks = listOfMaps(orderBook.get("asks"));
    if (bids.isEmpty() && asks.isEmpty()) {
      return response(List.of(new NoticeBlock("market_empty")), env, sessionId);
    }

    List<Section> sections = new ArrayList<>();
    if (!bids.isEmpty()) {
      sections.add(new Section("Покупки", null, toPairs(bids)));
    }
    if (!asks.isEmpty()) {
      sections.add(new Section("Продажи", null, toPairs(asks)));
    }
    List<ResponseBlock> blocks = new ArrayList<>();
    blocks.add(new SectionsBlock(sections));
    return response(blocks, env, sessionId);
  }

  private ChatResponseV2 marketTrades(
      ChatMessageEnvelope env,
      String sessionId,
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
      return error("BAD_INPUT", "Нужен тикер.", "Пример: /market_trades *Id* limit=10", env);
    }

    Integer limit = parseInt(opts.get("limit"));
    if (limit == null) limit = 10;
    if (!(limit == 1 || limit == 10 || limit == 100 || limit == 1000 || limit == 5000)) {
      return error(
          "BAD_LIMIT", "limit должен быть 1, 10, 100, 1000 или 5000.", "Проверь параметры.", env);
    }

    String from = opts.get("from");

    Map<String, Object> resp =
        downstream.marketTrades(
            token, engine, market, board, sec, from, limit, env.correlationId());
    List<Map<String, Object>> trades = listOfMaps(resp.get("trades"));
    if (trades.isEmpty()) {
      return response(List.of(new NoticeBlock("market_empty")), env, sessionId);
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
    List<ResponseBlock> blocks = new ArrayList<>();
    blocks.add(new TableBlock(List.of("NO", "TIME", "PRICE", "QTY", "SIDE"), rows, null));
    return response(blocks, env, sessionId);
  }

  private ChatResponseV2 pagedSections(
      ChatMessageEnvelope env,
      String sessionId,
      Integer page,
      String prefix,
      SectionsBlock sections,
      String introKey) {
    List<Section> list = sections.sections();
    if (list == null || list.isEmpty()) {
      return response(List.of(new NoticeBlock("Пока нет доступных разделов.")), env, sessionId);
    }
    int idx = pageIndex(list, page);
    Section selected = list.get(idx);
    List<ResponseBlock> blocks = new ArrayList<>();
    blocks.add(new NoticeBlock(introKey));
    blocks.add(new SectionsBlock(List.of(selected)));
    ActionBlock pager = buildPager(prefix, sessionId, idx, list.size());
    if (pager != null) {
      blocks.add(pager);
    }
    return response(blocks, env, sessionId);
  }

  private static ActionBlock buildPager(String prefix, String sessionId, int page, int totalPages) {
    List<ActionItem> actions = new ArrayList<>();
    if (page > 0) {
      actions.add(
          new ActionItem(prefix + "_prev", "Назад", prefix + ":" + sessionId + ":" + (page - 1)));
    }
    if (page + 1 < totalPages) {
      actions.add(
          new ActionItem(prefix + "_next", "Дальше", prefix + ":" + sessionId + ":" + (page + 1)));
    }
    return actions.isEmpty() ? null : new ActionBlock(actions);
  }

  private ActionBlock buildModuleActions(boolean linked, Set<String> perms) {
    List<ActionItem> actions = new ArrayList<>();
    if (menuBuilder.canMarket(perms, linked)) {
      actions.add(new ActionItem("menu_market", "Биржа", "cmd:menu_market"));
    }
    if (menuBuilder.canDev(perms, linked, devConsoleEnabled)) {
      actions.add(new ActionItem("menu_dev", "Dev", "cmd:menu_dev"));
      actions.add(new ActionItem("menu_db", "Работа с SQL", "cmd:db_menu"));
    }
    actions.add(new ActionItem("menu_account", "Аккаунт", "cmd:menu_account"));
    return actions.isEmpty() ? null : new ActionBlock(actions);
  }

  private ActionBlock buildMarketActions(boolean linked, Set<String> perms) {
    if (!menuBuilder.canMarket(perms, linked)) {
      return null;
    }
    List<ActionItem> actions = new ArrayList<>();
    actions.add(new ActionItem("menu_main", "Главное меню", "cmd:menu"));
    return new ActionBlock(actions);
  }

  private static ActionBlock buildBackAction() {
    return new ActionBlock(List.of(new ActionItem("menu_main", "Главное меню", "cmd:menu")));
  }

  private static ActionBlock buildDbActions(
      boolean includeFormat,
      boolean formatted,
      boolean includeMenuActions,
      boolean backToDbMenu,
      int columnIndex,
      int maxColumnIndex) {
    List<ActionItem> actions = new ArrayList<>();
    if (includeMenuActions) {
      actions.add(new ActionItem("db_tables", "Таблицы", "cmd:db:tables"));
      actions.add(new ActionItem("db_history", "Flyway", "cmd:db:history"));
    }
    if (formatted && maxColumnIndex > 0) {
      if (columnIndex > 0) {
        actions.add(new ActionItem("db_col_prev", "Столбец назад", "cmd:db:col:prev"));
      }
      if (columnIndex < maxColumnIndex) {
        actions.add(new ActionItem("db_col_next", "Столбец дальше", "cmd:db:col:next"));
      }
    }
    if (includeFormat) {
      if (formatted) {
        actions.add(new ActionItem("db_raw", "Сырой вид", "cmd:db:raw"));
      } else {
        actions.add(new ActionItem("db_format", "Форматировать", "cmd:db:format"));
      }
    }
    if (backToDbMenu) {
      actions.add(new ActionItem("menu_db", "БД меню", "cmd:db_menu"));
    } else {
      actions.add(new ActionItem("menu_main", "Главное меню", "cmd:menu"));
    }
    return actions.isEmpty() ? null : new ActionBlock(actions);
  }

  private SectionsBlock buildDbMenuSections() {
    List<Section> sections = new ArrayList<>();
    List<String> intro = new ArrayList<>();
    intro.add("Доступ только для dev-admin.");
    intro.add("SQL выполняется напрямую в базе gateway.");
    intro.add(
        "Результаты ограничены первыми " + DB_DEFAULT_MAX_ROWS + " строками (используй LIMIT).");
    sections.add(new Section("DB консоль", "Полный доступ /db.", intro));

    List<String> commands = new ArrayList<>();
    commands.add("/db <SQL> - выполнить SQL");
    commands.add("/db_tables - список таблиц");
    commands.add("/db_describe <schema.table> - структура таблицы");
    commands.add("/db_history - flyway_schema_history");
    sections.add(new Section("Команды", null, commands));
    return new SectionsBlock(sections);
  }

  private SectionsBlock buildMarketSections(MarketStatusInfo status) {
    List<Section> sections = new ArrayList<>();
    sections.add(buildMarketStatusSection(status));
    SectionsBlock base = menuBuilder.buildMarketMenu();
    if (base != null && base.sections() != null) {
      sections.addAll(base.sections());
    }
    return new SectionsBlock(sections);
  }

  private static Section buildMarketStatusSection(MarketStatusInfo status) {
    List<String> items = new ArrayList<>();
    String exchange = status == null ? "MOEX" : coalesce(status.exchange(), "MOEX");
    String board = status == null ? "TQBR" : coalesce(status.board(), "TQBR");
    items.add("Биржа: " + exchange + " (board " + board + ")");
    if (status == null || status.error() != null) {
      String error = status == null ? null : status.error();
      items.add(error == null ? "Статус: недоступен" : "Статус: недоступен (" + error + ")");
    } else {
      String label = describeTradingStatus(status.statusCode());
      String code = status.statusCode() == null ? "" : status.statusCode();
      items.add("Статус: " + label + (code.isBlank() ? "" : " (код " + code + ")"));
      if (status.time() != null && !status.time().isBlank()) {
        items.add("Время: " + status.time());
      }
    }
    items.add("Источник: MOEX ISS");
    return new Section("Статус биржи", null, items);
  }

  private MarketStatusInfo resolveMarketStatusForHelp(ChatMessageEnvelope env) {
    try {
      var res = gateway.resolve(providerCode(env), env.externalUserId());
      Set<String> perms =
          res != null && res.perms() != null ? new HashSet<>(res.perms()) : Set.of();
      boolean linked = res != null && res.linked();
      if (!menuBuilder.canMarket(perms, linked)) {
        return MarketStatusInfo.error(linked ? "нужен MARKETDATA_READ" : "нужна привязка");
      }
      return fetchMarketStatus(env, "stock", "shares", "TQBR", "SBER");
    } catch (RestClientResponseException e) {
      return MarketStatusInfo.error("ошибка запроса");
    }
  }

  private MarketStatusInfo fetchMarketStatus(
      ChatMessageEnvelope env, String engine, String market, String board, String sec) {
    try {
      var tokens = gateway.issueAccess(providerCode(env), env.externalUserId());
      if (tokens == null || tokens.accessToken() == null || tokens.accessToken().isBlank()) {
        return MarketStatusInfo.error("нет токена доступа");
      }
      Map<String, Object> resp =
          downstream.marketStatus(
              tokens.accessToken(), engine, market, board, sec, env.correlationId());
      return parseMarketStatus(resp, board, sec);
    } catch (RestClientResponseException e) {
      return MarketStatusInfo.error("ошибка биржи");
    } catch (Exception e) {
      return MarketStatusInfo.error("ошибка биржи");
    }
  }

  private static MarketStatusInfo parseMarketStatus(
      Map<String, Object> resp, String defaultBoard, String defaultSec) {
    Map<String, Object> status = mapOf(resp == null ? null : resp.get("status"));
    if (status.isEmpty()) {
      return MarketStatusInfo.error("нет данных");
    }
    String exchange = s(status.get("exchange"));
    String board = s(status.get("board"));
    String secId = s(status.get("secId"));
    String code = s(status.get("tradingStatus"));
    String time = s(status.get("time"));
    return new MarketStatusInfo(
        normalize(exchange, "MOEX"),
        normalize(board, defaultBoard),
        normalize(secId, defaultSec),
        normalize(code, null),
        normalize(time, null),
        null);
  }

  private ChatResponseV2 buildDbResponse(
      GatewayInternalClient.DbQueryResponse resp,
      DbPlan plan,
      ChatMessageEnvelope env,
      String sessionId,
      boolean menuContext,
      boolean formatted,
      int columnIndex) {
    List<ResponseBlock> blocks = new ArrayList<>();
    if (menuContext) {
      blocks.add(buildDbMenuSections());
    }
    String type = resp.type();
    if (type != null && type.equalsIgnoreCase("UPDATE")) {
      String message =
          resp.updated() == null ? "Готово." : "Готово. Затронуто строк: " + resp.updated();
      blocks.add(new NoticeBlock(message));
      blocks.add(buildDbActions(false, formatted, menuContext, menuContext, 0, 0));
      return response(blocks, env, sessionId, menuHints());
    }

    List<String> columns = resp.columns() == null ? List.of() : resp.columns();
    List<List<String>> rows = resp.rows() == null ? List.of() : resp.rows();
    if (columns.isEmpty()) {
      blocks.add(new NoticeBlock("Нет данных."));
      blocks.add(buildDbActions(false, formatted, menuContext, menuContext, 0, 0));
      return response(blocks, env, sessionId, menuHints());
    }
    if (resp.truncated()) {
      blocks.add(new NoticeBlock("Показаны первые " + plan.maxRows() + " строк. Используй LIMIT."));
    }
    DbTableSlice slice = sliceDbTable(columns, rows, formatted, columnIndex);
    blocks.add(new TableBlock(slice.columns(), slice.rows(), formatted ? "pretty" : null));
    blocks.add(
        buildDbActions(
            true, formatted, menuContext, true, slice.columnIndex(), slice.maxColumnIndex()));
    return response(blocks, env, sessionId, menuHints());
  }

  private static DbTableSlice sliceDbTable(
      List<String> columns, List<List<String>> rows, boolean formatted, int columnIndex) {
    List<String> safeColumns = columns == null ? List.of() : columns;
    List<List<String>> safeRows = rows == null ? List.of() : rows;
    if (!formatted || safeColumns.size() <= 2) {
      int maxIndex = Math.max(0, safeColumns.size() - 2);
      return new DbTableSlice(safeColumns, safeRows, 0, maxIndex);
    }
    int maxIndex = Math.max(0, safeColumns.size() - 2);
    int idx = normalizeColumnIndex(columnIndex, safeColumns.size());
    int secondIndex = idx + 1;
    List<String> slicedColumns = List.of(safeColumns.get(0), safeColumns.get(secondIndex));
    List<List<String>> slicedRows = new ArrayList<>();
    for (List<String> row : safeRows) {
      slicedRows.add(List.of(safeCell(row, 0), safeCell(row, secondIndex)));
    }
    return new DbTableSlice(slicedColumns, slicedRows, idx, maxIndex);
  }

  private static int normalizeColumnIndex(int columnIndex, int columnCount) {
    int maxIndex = Math.max(0, columnCount - 2);
    if (columnIndex < 0) {
      return 0;
    }
    if (columnIndex > maxIndex) {
      return maxIndex;
    }
    return columnIndex;
  }

  private static String safeCell(List<String> row, int index) {
    if (row == null || index < 0 || index >= row.size()) {
      return "";
    }
    String value = row.get(index);
    return value == null ? "" : value;
  }

  private DbFormatAction resolveDbFormatAction(String input) {
    String rest = extractDbSql(input);
    if (rest == null || rest.isBlank()) {
      return null;
    }
    String[] parts = rest.trim().toLowerCase(Locale.ROOT).split("\\s+");
    String cmd = parts[0];
    if ("format".equals(cmd) || "fmt".equals(cmd)) {
      return DbFormatAction.PRETTY;
    }
    if ("raw".equals(cmd)) {
      return DbFormatAction.RAW;
    }
    if ("col".equals(cmd) || "column".equals(cmd)) {
      if (parts.length > 1) {
        if ("next".equals(parts[1])) {
          return DbFormatAction.COLUMN_NEXT;
        }
        if ("prev".equals(parts[1])) {
          return DbFormatAction.COLUMN_PREV;
        }
      }
    }
    return null;
  }

  private ChatResponseV2 renderDbFromState(
      ChatMessageEnvelope env, String sessionId, DbFormatAction action) {
    DbState state = loadDbState(sessionId);
    if (state == null || state.sql() == null || state.sql().isBlank()) {
      return error("DB_STATE_EMPTY", "Нет данных для форматирования.", "Сначала выполни /db.", env);
    }
    DbPlan plan = new DbPlan(state.sql(), state.maxRows(), state.title(), null);
    boolean formatted = state.formatted();
    int columnIndex = state.columnIndex();
    switch (action) {
      case PRETTY -> {
        formatted = true;
        columnIndex = 0;
      }
      case RAW -> {
        formatted = false;
        columnIndex = 0;
      }
      case COLUMN_NEXT -> {
        formatted = true;
        columnIndex = columnIndex + 1;
      }
      case COLUMN_PREV -> {
        formatted = true;
        columnIndex = columnIndex - 1;
      }
    }
    try {
      var resp = gateway.dbQuery(plan.sql(), plan.maxRows());
      if (resp == null || !resp.ok()) {
        String hint = resp == null ? "Проверь запрос." : safeDbError(resp.error());
        return error("DB_FAILED", "Ошибка выполнения SQL.", hint, env);
      }
      int columnCount = resp.columns() == null ? 0 : resp.columns().size();
      columnIndex = normalizeColumnIndex(columnIndex, columnCount);
      if (!formatted) {
        columnIndex = 0;
      }
      storeDbState(sessionId, plan, state.menuContext(), formatted, columnIndex);
      return buildDbResponse(
          resp, plan, env, sessionId, state.menuContext(), formatted, columnIndex);
    } catch (RestClientResponseException e) {
      return error("DB_FAILED", "Ошибка выполнения SQL.", "Попробуй позже.", env);
    }
  }

  private void storeDbState(
      String sessionId, DbPlan plan, boolean menuContext, boolean formatted, int columnIndex) {
    if (sessionId == null || sessionId.isBlank() || plan == null) {
      return;
    }
    if (plan.sql() == null || plan.sql().isBlank()) {
      return;
    }
    DbState state =
        new DbState(plan.sql(), plan.maxRows(), plan.title(), menuContext, formatted, columnIndex);
    stateStore.set(dbStateKey(sessionId), ChatState.DB_LAST_QUERY, serializeDbState(state), null);
  }

  private DbState loadDbState(String sessionId) {
    if (sessionId == null || sessionId.isBlank()) {
      return null;
    }
    var entry = stateStore.get(dbStateKey(sessionId)).orElse(null);
    if (entry == null || entry.state() != ChatState.DB_LAST_QUERY) {
      return null;
    }
    return parseDbState(entry.payload());
  }

  private static String serializeDbState(DbState state) {
    if (state == null) {
      return null;
    }
    StringBuilder sb = new StringBuilder();
    appendParam(sb, "sql", state.sql());
    appendParam(sb, "max", String.valueOf(state.maxRows()));
    appendParam(sb, "title", state.title());
    appendParam(sb, "menu", state.menuContext() ? "1" : "0");
    appendParam(sb, "fmt", state.formatted() ? "1" : "0");
    appendParam(sb, "col", String.valueOf(state.columnIndex()));
    return sb.toString();
  }

  private static DbState parseDbState(String payload) {
    if (payload == null || payload.isBlank()) {
      return null;
    }
    Map<String, String> values = parsePayload(payload);
    String sql = normalizeParam(values.get("sql"));
    Integer max = parseInt(values.get("max"));
    String title = normalizeParam(values.get("title"));
    boolean menu = "1".equals(values.get("menu"));
    boolean formatted = "1".equals(values.get("fmt"));
    Integer col = parseInt(values.get("col"));
    int columnIndex = col == null ? 0 : col;
    if (sql == null || max == null || max < 1) {
      return null;
    }
    return new DbState(sql, max, title, menu, formatted, columnIndex);
  }

  private static Map<String, String> parsePayload(String payload) {
    Map<String, String> values = new HashMap<>();
    for (String token : payload.split("&")) {
      if (token == null || token.isBlank()) continue;
      int pos = token.indexOf('=');
      if (pos <= 0 || pos >= token.length() - 1) continue;
      String key = decode(token.substring(0, pos));
      String value = decode(token.substring(pos + 1));
      values.put(key, value);
    }
    return values;
  }

  private static String dbStateKey(String sessionId) {
    return sessionId + "|db";
  }

  private static boolean isDbMenuCallback(ChatMessageEnvelope env) {
    String callback = env.callbackData();
    if (callback == null || callback.isBlank()) {
      return false;
    }
    return callback.trim().startsWith("cmd:db:");
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

  private SectionsBlock buildAccountSections(
      GatewayInternalClient.ResolveResponse res, boolean revealDetails) {
    List<Section> sections = new ArrayList<>();
    sections.add(buildAccountStatusSection(res, revealDetails));
    SectionsBlock base = menuBuilder.buildAccountMenu();
    if (base != null && base.sections() != null) {
      sections.addAll(base.sections());
    }
    return new SectionsBlock(sections);
  }

  private static Section buildAccountStatusSection(
      GatewayInternalClient.ResolveResponse res, boolean revealDetails) {
    List<String> items = new ArrayList<>();
    if (res == null || !res.linked()) {
      items.add("Привязка: нет");
      items.add("Подключи /login или /register");
      return new Section("Аккаунт", null, items);
    }
    items.add("Привязка: активна");
    if (res.login() != null && !res.login().isBlank()) {
      items.add("Логин: " + res.login());
    }
    if (revealDetails) {
      if (res.userId() != null) {
        items.add("ID пользователя: " + res.userId());
      }
      items.add("Роли: " + formatList(res.roles()));
      items.add("Права: " + formatPerms(res.perms()));
    }
    return new Section("Аккаунт", null, items);
  }

  private static boolean canRevealAccountDetails(Set<String> perms, boolean linked) {
    if (!linked || perms == null || perms.isEmpty()) {
      return false;
    }
    if (perms.contains("DEVGOD")) {
      return true;
    }
    for (String perm : perms) {
      if (ADMIN_PERMS.contains(perm)) {
        return true;
      }
    }
    return false;
  }

  private static String formatPerms(List<String> perms) {
    if (perms == null || perms.isEmpty()) {
      return "нет";
    }
    int limit = 5;
    List<String> head = perms.size() <= limit ? perms : perms.subList(0, limit);
    String suffix = perms.size() > limit ? " + " + (perms.size() - limit) + "..." : "";
    return perms.size() + " (" + String.join(", ", head) + ")" + suffix;
  }

  private static String formatList(List<String> values) {
    if (values == null || values.isEmpty()) {
      return "нет";
    }
    return String.join(", ", values);
  }

  private static String describeTradingStatus(String code) {
    if (code == null || code.isBlank()) {
      return "неизвестно";
    }
    String normalized = code.trim().toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case "T" -> "торги идут";
      case "A" -> "аукцион открытия";
      case "B" -> "подготовка к торгам";
      case "C" -> "аукцион закрытия";
      case "N" -> "торги закрыты";
      case "D" -> "торги завершены";
      default -> "статус " + normalized;
    };
  }

  private static String coalesce(String value, String fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return value;
  }

  private static String normalize(String value, String fallback) {
    if (value == null) {
      return fallback;
    }
    String trimmed = value.trim();
    if (trimmed.isBlank()) {
      return fallback;
    }
    return trimmed;
  }

  private static ActionBlock buildMarketPager(
      String sessionId, int limit, int offset, int actualSize) {
    List<ActionItem> actions = new ArrayList<>();
    if (offset > 0) {
      actions.add(
          new ActionItem(
              "market_prev", "Назад", "mi:" + sessionId + ":" + Math.max(0, offset - limit)));
    }
    if (actualSize >= limit) {
      actions.add(
          new ActionItem("market_next", "Дальше", "mi:" + sessionId + ":" + (offset + limit)));
    }
    return actions.isEmpty() ? null : new ActionBlock(actions);
  }

  private static Section pickSection(List<Section> sections, Integer page) {
    if (sections == null || sections.isEmpty()) {
      return null;
    }
    int idx = pageIndex(sections, page);
    return sections.get(idx);
  }

  private static int pageIndex(List<?> sections, Integer page) {
    int total = sections.size();
    int idx = page == null ? 0 : Math.max(0, Math.min(page, total - 1));
    return idx;
  }

  private static ChatResponseV2 error(
      String code, String message, String hint, ChatMessageEnvelope env) {
    return response(List.of(new ErrorBlock(code, message, hint, null)), env, env.sessionId());
  }

  private static ChatResponseV2 response(
      List<ResponseBlock> blocks, ChatMessageEnvelope env, String sessionId) {
    return response(blocks, env, sessionId, null);
  }

  private static ChatResponseV2 response(
      List<ResponseBlock> blocks, ChatMessageEnvelope env, String sessionId, UiHintsV2 hints) {
    return new ChatResponseV2(blocks, env.correlationId(), sessionId, env.locale(), hints);
  }

  private static UiHintsV2 menuHints() {
    return new UiHintsV2(true, false, null);
  }

  private static String ensureSessionId(ChatMessageEnvelope env, String fallback) {
    if (env.sessionId() != null && !env.sessionId().isBlank()) {
      return env.sessionId();
    }
    if (fallback != null && !fallback.isBlank()) {
      return fallback;
    }
    return UUID.randomUUID().toString();
  }

  private static String extractInput(ChatMessageEnvelope env) {
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
    return trimmed;
  }

  private static PageRequest parsePageRequest(String input) {
    if (input == null || input.isBlank()) {
      return null;
    }
    String trimmed = input.trim();
    if (!trimmed.startsWith("h:") && !trimmed.startsWith("m:") && !trimmed.startsWith("mi:")) {
      return null;
    }
    String[] parts = trimmed.split(":", 3);
    if (parts.length < 3) {
      return null;
    }
    Integer page = parseInt(parts[2]);
    if (page == null) {
      return null;
    }
    return new PageRequest(parts[0], parts[1], page);
  }

  private void storeMarketParams(String sessionId, MarketParams params) {
    if (sessionId == null || sessionId.isBlank() || params == null) {
      return;
    }
    stateStore.set(
        marketStateKey(sessionId),
        ChatState.MARKET_INSTRUMENTS_PAGE,
        serializeMarketParams(params),
        null);
  }

  private MarketParams loadMarketParams(String sessionId) {
    if (sessionId == null || sessionId.isBlank()) {
      return null;
    }
    var entry = stateStore.get(marketStateKey(sessionId)).orElse(null);
    if (entry == null || entry.state() != ChatState.MARKET_INSTRUMENTS_PAGE) {
      return null;
    }
    return parseMarketParams(entry.payload());
  }

  private static String marketStateKey(String sessionId) {
    return sessionId + "|market";
  }

  private static String serializeMarketParams(MarketParams params) {
    StringBuilder sb = new StringBuilder();
    appendParam(sb, "limit", String.valueOf(params.limit()));
    appendParam(sb, "filter", params.filter());
    appendParam(sb, "board", params.board());
    appendParam(sb, "engine", params.engine());
    appendParam(sb, "market", params.market());
    return sb.toString();
  }

  private static void appendParam(StringBuilder sb, String key, String value) {
    if (value == null || value.isBlank()) {
      return;
    }
    if (sb.length() > 0) {
      sb.append("&");
    }
    sb.append(encode(key)).append("=").append(encode(value));
  }

  private static MarketParams parseMarketParams(String payload) {
    if (payload == null || payload.isBlank()) {
      return null;
    }
    Map<String, String> values = new HashMap<>();
    for (String token : payload.split("&")) {
      if (token == null || token.isBlank()) continue;
      int pos = token.indexOf('=');
      if (pos <= 0 || pos >= token.length() - 1) continue;
      String key = decode(token.substring(0, pos));
      String value = decode(token.substring(pos + 1));
      values.put(key, value);
    }
    Integer limit = parseInt(values.get("limit"));
    if (limit == null || limit < 1) {
      return null;
    }
    String filter = normalizeParam(values.get("filter"));
    String board = normalizeParam(values.get("board"));
    String engine = normalizeParam(values.get("engine"));
    String market = normalizeParam(values.get("market"));
    if (board == null) board = "TQBR";
    if (engine == null) engine = "stock";
    if (market == null) market = "shares";
    return new MarketParams(limit, filter, board, engine, market);
  }

  private static String normalizeParam(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String decode(String value) {
    return URLDecoder.decode(value, StandardCharsets.UTF_8);
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

    // menu
    m.put("/menu", "/menu");
    m.put("/menu_market", "/menu_market");
    m.put("/menu_dev", "/menu_dev");
    m.put("/menu_account", "/menu_account");
    m.put("/menu_accaunt", "/menu_account");
    m.put("/меню", "/menu");

    // db
    m.put("/db", "/db");
    m.put("/db_menu", "/db_menu");

    // market
    m.put("/market", "/market");
    m.put("/рынок", "/market");

    return m;
  }

  private static String providerCode(ChatMessageEnvelope env) {
    String ch = env.channel() == null ? "" : env.channel().trim();
    if (ch.isBlank()) return "TELEGRAM";
    return ch.toUpperCase(Locale.ROOT);
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

  private static List<String> toPairs(List<Map<String, Object>> entries) {
    List<String> out = new ArrayList<>();
    for (Map<String, Object> entry : entries) {
      out.add(n(entry.get("price")) + " x " + n(entry.get("quantity")));
    }
    return out;
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

  private record Parsed(String cmd, String arg1, String arg2, String arg3) {}

  private record PageRequest(String kind, String sessionId, int page) {}

  private record MarketStatusInfo(
      String exchange, String board, String secId, String statusCode, String time, String error) {
    private static MarketStatusInfo error(String error) {
      return new MarketStatusInfo(null, null, null, null, null, error);
    }
  }

  private enum DbFormatAction {
    PRETTY,
    RAW,
    COLUMN_NEXT,
    COLUMN_PREV
  }

  private record DbPlan(String sql, int maxRows, String title, String error) {}

  private record DbState(
      String sql,
      int maxRows,
      String title,
      boolean menuContext,
      boolean formatted,
      int columnIndex) {}

  private record DbTableSlice(
      List<String> columns, List<List<String>> rows, int columnIndex, int maxColumnIndex) {}

  private record MarketParams(
      int limit, String filter, String board, String engine, String market) {}
}
