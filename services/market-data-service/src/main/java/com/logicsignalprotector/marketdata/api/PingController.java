package com.logicsignalprotector.marketdata.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class PingController {

    @GetMapping("/ping")
    public Map<String, String> ping() {
        return Map.of(
                "service", "market-data-service",
                "status", "ok"
        );
    }
}
