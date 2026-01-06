package com.logicsignalprotector.marketdata.api;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PingController {

  @GetMapping("/ping")
  public Map<String, String> ping() {
    return Map.of("service", "market-data-service", "status", "market-data-service");
  }
}
