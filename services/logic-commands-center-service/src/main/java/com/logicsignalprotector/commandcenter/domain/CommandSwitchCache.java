package com.logicsignalprotector.commandcenter.domain;

import com.logicsignalprotector.commandcenter.client.GatewayInternalClient;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class CommandSwitchCache {

  private final GatewayInternalClient gateway;
  private final Duration ttl;

  private volatile Map<String, Boolean> cache = Map.of();
  private volatile Instant expiresAt = Instant.EPOCH;

  public CommandSwitchCache(
      GatewayInternalClient gateway, @Value("${command.switch.cache-ttl:PT10S}") Duration ttl) {
    this.gateway = gateway;
    this.ttl = ttl == null ? Duration.ofSeconds(10) : ttl;
  }

  public boolean isEnabled(String commandCode) {
    refreshIfNeeded();
    Boolean val = cache.get(commandCode);
    return val == null || val;
  }

  private void refreshIfNeeded() {
    if (Instant.now().isBefore(expiresAt)) {
      return;
    }
    synchronized (this) {
      if (Instant.now().isBefore(expiresAt)) {
        return;
      }
      try {
        Map<String, Boolean> next = new HashMap<>();
        var list = gateway.listCommandSwitches();
        if (list != null && list.switches() != null) {
          for (var s : list.switches()) {
            if (s != null && s.commandCode() != null) {
              next.put(s.commandCode(), s.enabled());
            }
          }
        }
        cache = next;
        expiresAt = Instant.now().plus(ttl);
      } catch (Exception e) {
        log.warn("Failed to refresh command switches: {}", e.getMessage());
        expiresAt = Instant.now().plus(ttl);
      }
    }
  }
}
