package com.logicsignalprotector.marketdata.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.logicsignalprotector.marketdata.config.MoexProperties;
import com.logicsignalprotector.marketdata.dto.CandleDto;
import com.logicsignalprotector.marketdata.dto.InstrumentDto;
import com.logicsignalprotector.marketdata.dto.MarketStatusDto;
import com.logicsignalprotector.marketdata.dto.OrderBookDto;
import com.logicsignalprotector.marketdata.dto.OrderBookEntryDto;
import com.logicsignalprotector.marketdata.dto.QuoteDto;
import com.logicsignalprotector.marketdata.dto.TradeDto;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public class MoexClient {
  private static final Logger log = LoggerFactory.getLogger(MoexClient.class);
  private static final DateTimeFormatter ISS_DATE_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
  private static final DateTimeFormatter ISS_TIME =
      DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

  private final WebClient webClient;
  private final Cache<String, JsonNode> cache;
  private final MoexProperties properties;

  public MoexClient(WebClient moexWebClient, MoexProperties properties) {
    this.webClient = moexWebClient;
    this.properties = properties;
    this.cache =
        Caffeine.newBuilder().expireAfterWrite(properties.cacheTtl()).maximumSize(1000).build();
  }

  public List<InstrumentDto> getInstruments(
      String engine, String market, String board, Optional<String> filter, int limit, int offset) {
    String path =
        String.format("/engines/%s/markets/%s/boards/%s/securities.json", engine, market, board);
    Map<String, String> params = new HashMap<>();
    params.put("iss.only", "securities");
    params.put(
        "securities.columns", "SECID,SHORTNAME,SECNAME,LOTSIZE,PREVPRICE,LAST,CURRENCYID,BOARDID");
    params.put("start", Integer.toString(offset));
    params.put("limit", Integer.toString(limit));
    JsonNode root = get(path, params);
    List<InstrumentDto> instruments = parseInstruments(root);
    if (filter.isPresent()) {
      String query = filter.get().toLowerCase(Locale.ROOT);
      instruments =
          instruments.stream()
              .filter(
                  item ->
                      containsIgnoreCase(item.secId(), query)
                          || containsIgnoreCase(item.name(), query)
                          || containsIgnoreCase(item.shortName(), query))
              .collect(Collectors.toList());
    }
    return instruments;
  }

  public QuoteDto getQuote(String engine, String market, String board, String sec) {
    String path =
        String.format(
            "/engines/%s/markets/%s/boards/%s/securities/%s.json", engine, market, board, sec);
    Map<String, String> params = new HashMap<>();
    params.put("iss.only", "securities,marketdata");
    params.put("securities.columns", "SECID,BOARDID");
    params.put("marketdata.columns", "LAST,CHANGE,LASTTOPREVPRICE,VOLTODAY,SYSTIME");
    JsonNode root = get(path, params);
    return parseQuote(root, sec, board);
  }

  public List<CandleDto> getCandles(
      String engine,
      String market,
      String board,
      String sec,
      int interval,
      OffsetDateTime from,
      OffsetDateTime till) {
    String path =
        String.format(
            "/engines/%s/markets/%s/boards/%s/securities/%s/candles.json",
            engine, market, board, sec);
    Map<String, String> params = new HashMap<>();
    params.put("iss.only", "candles");
    params.put("candles.columns", "begin,end,open,close,high,low,volume");
    params.put("interval", Integer.toString(interval));
    if (from != null) {
      params.put("from", formatIssDate(from));
    }
    if (till != null) {
      params.put("till", formatIssDate(till));
    }
    JsonNode root = get(path, params);
    return parseCandles(root);
  }

  public OrderBookDto getOrderBook(String engine, String market, String board, String sec) {
    String path =
        String.format(
            "/engines/%s/markets/%s/boards/%s/securities/%s/orderbook.json",
            engine, market, board, sec);
    Map<String, String> params = new HashMap<>();
    params.put("iss.only", "orderbook");
    params.put("orderbook.columns", "BUYSELL,PRICE,QUANTITY");
    JsonNode root = get(path, params);
    return parseOrderBook(root, sec, board);
  }

  public List<TradeDto> getTrades(
      String engine,
      String market,
      String board,
      String sec,
      Optional<String> from,
      Optional<Integer> limit) {
    String path =
        String.format(
            "/engines/%s/markets/%s/boards/%s/securities/%s/trades.json",
            engine, market, board, sec);
    Map<String, String> params = new HashMap<>();
    params.put("iss.only", "trades");
    params.put("trades.columns", "TRADENO,TRADETIME,PRICE,QUANTITY,BUYSELL");
    from.ifPresent(value -> params.put("from", value));
    limit.ifPresent(value -> params.put("limit", Integer.toString(value)));
    JsonNode root = get(path, params);
    return parseTrades(root);
  }

  public MarketStatusDto getMarketStatus(String engine, String market, String board, String sec) {
    String path =
        String.format(
            "/engines/%s/markets/%s/boards/%s/securities/%s.json", engine, market, board, sec);
    Map<String, String> params = new HashMap<>();
    params.put("iss.only", "marketdata");
    params.put("marketdata.columns", "TRADINGSTATUS,SYSTIME");
    JsonNode root = get(path, params);
    return parseMarketStatus(root, sec, board);
  }

  private JsonNode get(String path, Map<String, String> params) {
    String cacheKey = buildCacheKey(path, params);
    return cache.get(cacheKey, key -> fetch(path, params));
  }

  private JsonNode fetch(String path, Map<String, String> params) {
    log.info("MOEX ISS request {} params={}", path, params);
    JsonNode response =
        webClient
            .get()
            .uri(
                uriBuilder -> {
                  uriBuilder.path(path);
                  params.forEach(uriBuilder::queryParam);
                  return uriBuilder.build();
                })
            .accept(MediaType.APPLICATION_JSON)
            .exchangeToMono(
                clientResponse -> {
                  if (clientResponse.statusCode().isError()) {
                    return clientResponse
                        .bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(
                            body ->
                                Mono.error(
                                    new MoexClientException(
                                        "MOEX ISS error "
                                            + clientResponse.statusCode().value()
                                            + " "
                                            + body)));
                  }
                  MediaType contentType =
                      clientResponse
                          .headers()
                          .contentType()
                          .orElse(MediaType.APPLICATION_OCTET_STREAM);
                  if (isJson(contentType)) {
                    return clientResponse.bodyToMono(JsonNode.class);
                  }
                  return clientResponse
                      .bodyToMono(String.class)
                      .defaultIfEmpty("")
                      .flatMap(
                          body ->
                              Mono.error(
                                  new MoexClientException(buildNonJsonMessage(contentType, body))));
                })
            .block(properties.timeout());
    if (response == null) {
      throw new MoexClientException("MOEX ISS returned empty response");
    }
    return response;
  }

  private static boolean isJson(MediaType contentType) {
    if (contentType == null) {
      return false;
    }
    if (MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
      return true;
    }
    String subtype = contentType.getSubtype();
    return subtype != null && subtype.endsWith("+json");
  }

  private static String buildNonJsonMessage(MediaType contentType, String body) {
    String message = "MOEX ISS returned non-JSON response (" + contentType + ").";
    if (body != null) {
      String lower = body.toLowerCase(Locale.ROOT);
      if (lower.contains("subscribers")) {
        message =
            "MOEX ISS orderbook is subscription-only (non-JSON response: " + contentType + ").";
      }
    }
    return message;
  }

  private List<InstrumentDto> parseInstruments(JsonNode root) {
    JsonNode section = section(root, "securities");
    Map<String, Integer> idx = indexColumns(section);
    return toRows(section).stream()
        .map(
            row ->
                new InstrumentDto(
                    text(row, idx, "SECID"),
                    text(row, idx, "SHORTNAME"),
                    text(row, idx, "SECNAME"),
                    integer(row, idx, "LOTSIZE"),
                    decimal(row, idx, "PREVPRICE"),
                    decimal(row, idx, "LAST"),
                    text(row, idx, "CURRENCYID"),
                    text(row, idx, "BOARDID")))
        .collect(Collectors.toList());
  }

  private QuoteDto parseQuote(JsonNode root, String sec, String board) {
    JsonNode section = section(root, "marketdata");
    Map<String, Integer> idx = indexColumns(section);
    List<JsonNode> rows = toRows(section);
    if (rows.isEmpty()) {
      throw new MoexClientException("MOEX ISS returned empty marketdata for " + sec);
    }
    JsonNode row = rows.get(0);
    return new QuoteDto(
        sec,
        board,
        decimal(row, idx, "LAST"),
        decimal(row, idx, "CHANGE"),
        decimal(row, idx, "LASTTOPREVPRICE"),
        decimal(row, idx, "VOLTODAY"),
        dateTime(row, idx, "SYSTIME"));
  }

  private List<CandleDto> parseCandles(JsonNode root) {
    JsonNode section = section(root, "candles");
    Map<String, Integer> idx = indexColumns(section);
    return toRows(section).stream()
        .map(
            row ->
                new CandleDto(
                    dateTime(row, idx, "begin"),
                    dateTime(row, idx, "end"),
                    decimal(row, idx, "open"),
                    decimal(row, idx, "close"),
                    decimal(row, idx, "high"),
                    decimal(row, idx, "low"),
                    decimal(row, idx, "volume")))
        .collect(Collectors.toList());
  }

  private OrderBookDto parseOrderBook(JsonNode root, String sec, String board) {
    JsonNode section = section(root, "orderbook");
    Map<String, Integer> idx = indexColumns(section);
    List<OrderBookEntryDto> bids = new ArrayList<>();
    List<OrderBookEntryDto> asks = new ArrayList<>();
    for (JsonNode row : toRows(section)) {
      String side = text(row, idx, "BUYSELL");
      OrderBookEntryDto entry =
          new OrderBookEntryDto(side, decimal(row, idx, "PRICE"), decimal(row, idx, "QUANTITY"));
      if ("B".equalsIgnoreCase(side)) {
        bids.add(entry);
      } else if ("S".equalsIgnoreCase(side)) {
        asks.add(entry);
      }
    }
    bids.sort(Comparator.comparing(OrderBookEntryDto::price).reversed());
    asks.sort(Comparator.comparing(OrderBookEntryDto::price));
    return new OrderBookDto(sec, board, null, bids, asks);
  }

  private List<TradeDto> parseTrades(JsonNode root) {
    JsonNode section = section(root, "trades");
    Map<String, Integer> idx = indexColumns(section);
    return toRows(section).stream()
        .map(
            row ->
                new TradeDto(
                    longValue(row, idx, "TRADENO"),
                    dateTime(row, idx, "TRADETIME"),
                    decimal(row, idx, "PRICE"),
                    decimal(row, idx, "QUANTITY"),
                    text(row, idx, "BUYSELL")))
        .collect(Collectors.toList());
  }

  private MarketStatusDto parseMarketStatus(JsonNode root, String sec, String board) {
    JsonNode section = section(root, "marketdata");
    Map<String, Integer> idx = indexColumns(section);
    List<JsonNode> rows = toRows(section);
    if (rows.isEmpty()) {
      throw new MoexClientException("MOEX ISS returned empty marketdata for " + sec);
    }
    JsonNode row = rows.get(0);
    return new MarketStatusDto(
        "MOEX", board, sec, text(row, idx, "TRADINGSTATUS"), dateTime(row, idx, "SYSTIME"));
  }

  private static boolean containsIgnoreCase(String value, String query) {
    return value != null && value.toLowerCase(Locale.ROOT).contains(query);
  }

  private static String formatIssDate(OffsetDateTime dateTime) {
    return ISS_DATE_TIME.format(dateTime.withOffsetSameInstant(ZoneOffset.UTC));
  }

  private static JsonNode section(JsonNode root, String name) {
    JsonNode section = root.get(name);
    if (section == null) {
      throw new MoexClientException("MOEX ISS response missing section " + name);
    }
    return section;
  }

  private static Map<String, Integer> indexColumns(JsonNode section) {
    JsonNode columns = section.get("columns");
    if (columns == null || !columns.isArray()) {
      throw new MoexClientException("MOEX ISS response has invalid columns");
    }
    Map<String, Integer> idx = new HashMap<>();
    for (int i = 0; i < columns.size(); i++) {
      idx.put(columns.get(i).asText(), i);
    }
    return idx;
  }

  private static List<JsonNode> toRows(JsonNode section) {
    JsonNode data = section.get("data");
    if (data == null || !data.isArray()) {
      throw new MoexClientException("MOEX ISS response has invalid data");
    }
    List<JsonNode> rows = new ArrayList<>();
    data.forEach(rows::add);
    return rows;
  }

  private static String text(JsonNode row, Map<String, Integer> idx, String column) {
    JsonNode value = value(row, idx, column);
    return value == null || value.isNull() ? null : value.asText();
  }

  private static BigDecimal decimal(JsonNode row, Map<String, Integer> idx, String column) {
    JsonNode value = value(row, idx, column);
    if (value == null || value.isNull()) {
      return null;
    }
    String text = value.asText();
    return text == null || text.isBlank() ? null : new BigDecimal(text);
  }

  private static Integer integer(JsonNode row, Map<String, Integer> idx, String column) {
    JsonNode value = value(row, idx, column);
    if (value == null || value.isNull()) {
      return null;
    }
    return value.asInt();
  }

  private static Long longValue(JsonNode row, Map<String, Integer> idx, String column) {
    JsonNode value = value(row, idx, column);
    if (value == null || value.isNull()) {
      return null;
    }
    return value.asLong();
  }

  private static OffsetDateTime dateTime(JsonNode row, Map<String, Integer> idx, String column) {
    JsonNode value = value(row, idx, column);
    if (value == null || value.isNull()) {
      return null;
    }
    return parseDateTime(value.asText());
  }

  private static OffsetDateTime parseDateTime(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return OffsetDateTime.parse(value);
    } catch (Exception ignored) {
      // fallback below
    }
    try {
      LocalDateTime localDateTime = LocalDateTime.parse(value, ISS_DATE_TIME);
      return localDateTime.atOffset(ZoneOffset.UTC);
    } catch (Exception ex) {
      // fallback below
    }
    try {
      LocalDateTime localDateTime =
          LocalDateTime.of(
              OffsetDateTime.now(ZoneOffset.UTC).toLocalDate(),
              java.time.LocalTime.parse(value, ISS_TIME));
      return localDateTime.atOffset(ZoneOffset.UTC);
    } catch (Exception ex) {
      throw new MoexClientException("Cannot parse ISS date time: " + value, ex);
    }
  }

  private static JsonNode value(JsonNode row, Map<String, Integer> idx, String column) {
    Integer pos = idx.get(column);
    if (pos == null || pos < 0 || pos >= row.size()) {
      return null;
    }
    return row.get(pos);
  }

  private static String buildCacheKey(String path, Map<String, String> params) {
    String query =
        params.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> entry.getKey() + "=" + entry.getValue())
            .collect(Collectors.joining("&"));
    return path + "?" + query;
  }
}
