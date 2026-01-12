package com.logicsignalprotector.marketdata.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "market.moex")
public record MoexProperties(String baseUrl, Duration timeout, Duration cacheTtl) {}
