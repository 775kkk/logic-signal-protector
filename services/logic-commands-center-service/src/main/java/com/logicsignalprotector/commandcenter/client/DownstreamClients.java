package com.logicsignalprotector.commandcenter.client;

import java.util.Map;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

@Service
public class DownstreamClients {

  private final RestClient market;
  private final RestClient alerts;
  private final RestClient broker;

  public DownstreamClients(
      RestClient.Builder builder,
      @Value("${services.market-data.base-url}") String marketBase,
      @Value("${services.alerts.base-url}") String alertsBase,
      @Value("${services.broker.base-url}") String brokerBase) {
    this.market = builder.baseUrl(marketBase).build();
    this.alerts = builder.baseUrl(alertsBase).build();
    this.broker = builder.baseUrl(brokerBase).build();
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> marketInstruments(
      String bearerToken,
      String engine,
      String market,
      String board,
      String filter,
      Integer limit,
      Integer offset) {
    return this.market
        .get()
        .uri(
            uriBuilder ->
                uriBuilder
                    .path("/api/market/v1/instruments")
                    .queryParams(
                        marketParams(
                            params -> {
                              add(params, "engine", engine);
                              add(params, "market", market);
                              add(params, "board", board);
                              add(params, "filter", filter);
                              add(params, "limit", limit);
                              add(params, "offset", offset);
                            }))
                    .build())
        .header("Authorization", "Bearer " + bearerToken)
        .retrieve()
        .body(Map.class);
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> marketQuote(
      String bearerToken, String engine, String market, String board, String sec) {
    return this.market
        .get()
        .uri(
            uriBuilder ->
                uriBuilder
                    .path("/api/market/v1/quotes")
                    .queryParams(
                        marketParams(
                            params -> {
                              add(params, "engine", engine);
                              add(params, "market", market);
                              add(params, "board", board);
                              add(params, "sec", sec);
                            }))
                    .build())
        .header("Authorization", "Bearer " + bearerToken)
        .retrieve()
        .body(Map.class);
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> marketCandles(
      String bearerToken,
      String engine,
      String market,
      String board,
      String sec,
      Integer interval,
      String from,
      String till) {
    return this.market
        .get()
        .uri(
            uriBuilder ->
                uriBuilder
                    .path("/api/market/v1/candles")
                    .queryParams(
                        marketParams(
                            params -> {
                              add(params, "engine", engine);
                              add(params, "market", market);
                              add(params, "board", board);
                              add(params, "sec", sec);
                              add(params, "interval", interval);
                              add(params, "from", from);
                              add(params, "till", till);
                            }))
                    .build())
        .header("Authorization", "Bearer " + bearerToken)
        .retrieve()
        .body(Map.class);
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> marketOrderBook(
      String bearerToken, String engine, String market, String board, String sec, Integer depth) {
    return this.market
        .get()
        .uri(
            uriBuilder ->
                uriBuilder
                    .path("/api/market/v1/orderbook")
                    .queryParams(
                        marketParams(
                            params -> {
                              add(params, "engine", engine);
                              add(params, "market", market);
                              add(params, "board", board);
                              add(params, "sec", sec);
                              add(params, "depth", depth);
                            }))
                    .build())
        .header("Authorization", "Bearer " + bearerToken)
        .retrieve()
        .body(Map.class);
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> marketTrades(
      String bearerToken,
      String engine,
      String market,
      String board,
      String sec,
      String from,
      Integer limit) {
    return this.market
        .get()
        .uri(
            uriBuilder ->
                uriBuilder
                    .path("/api/market/v1/trades")
                    .queryParams(
                        marketParams(
                            params -> {
                              add(params, "engine", engine);
                              add(params, "market", market);
                              add(params, "board", board);
                              add(params, "sec", sec);
                              add(params, "from", from);
                              add(params, "limit", limit);
                            }))
                    .build())
        .header("Authorization", "Bearer " + bearerToken)
        .retrieve()
        .body(Map.class);
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> alertsSecureSample(String bearerToken) {
    return alerts
        .get()
        .uri("/api/alerts/secure-sample")
        .header("Authorization", "Bearer " + bearerToken)
        .retrieve()
        .body(Map.class);
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> brokerSecureSample(String bearerToken) {
    return broker
        .get()
        .uri("/api/broker/secure-sample")
        .header("Authorization", "Bearer " + bearerToken)
        .retrieve()
        .body(Map.class);
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> brokerTradeSample(String bearerToken) {
    return broker
        .post()
        .uri("/api/broker/trade-sample")
        .header("Authorization", "Bearer " + bearerToken)
        .body(Map.of())
        .retrieve()
        .body(Map.class);
  }

  private static MultiValueMap<String, String> marketParams(
      Consumer<MultiValueMap<String, String>> customizer) {
    MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
    customizer.accept(params);
    return params;
  }

  private static void add(MultiValueMap<String, String> params, String key, Object value) {
    if (value == null) return;
    String text = value.toString();
    if (text.isBlank()) return;
    params.add(key, text);
  }
}
