package com.logicsignalprotector.commandcenter.client;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
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
  public Map<String, Object> marketSecureSample(String bearerToken) {
    return market
        .get()
        .uri("/api/market-data/secure-sample")
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
}
