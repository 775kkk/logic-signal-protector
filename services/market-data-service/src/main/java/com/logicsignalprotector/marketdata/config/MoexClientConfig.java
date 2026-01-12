package com.logicsignalprotector.marketdata.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Configuration
public class MoexClientConfig {

  @Bean
  public WebClient moexWebClient(MoexProperties properties) {
    Duration timeout = properties.timeout();
    HttpClient httpClient =
        HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(timeout.toMillis()))
            .doOnConnected(
                conn ->
                    conn.addHandlerLast(
                        new ReadTimeoutHandler(timeout.toMillis(), TimeUnit.MILLISECONDS)));
    return WebClient.builder()
        .baseUrl(properties.baseUrl())
        .clientConnector(new ReactorClientHttpConnector(httpClient))
        .build();
  }
}
