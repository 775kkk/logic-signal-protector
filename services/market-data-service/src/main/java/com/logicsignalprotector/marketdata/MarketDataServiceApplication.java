package com.logicsignalprotector.marketdata;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MarketDataServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(MarketDataServiceApplication.class, args);
  }
}
