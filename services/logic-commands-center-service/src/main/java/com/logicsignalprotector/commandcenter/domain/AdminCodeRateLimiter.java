package com.logicsignalprotector.commandcenter.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Step 1.4: lightweight in-memory rate limiter for /adminlogin code attempts.
 *
 * <p>Goal: avoid brute force in dev environments without introducing Redis here.
 */
@Service
public class AdminCodeRateLimiter {

  private final ConcurrentMap<String, Window> map = new ConcurrentHashMap<>();
  private final Duration window;
  private final int maxAttempts;

  public AdminCodeRateLimiter(
      @Value("${dev.adminlogin.rate.window:PT10M}") Duration window,
      @Value("${dev.adminlogin.rate.max-attempts:5}") int maxAttempts) {
    this.window = window;
    this.maxAttempts = maxAttempts;
  }

  /**
   * @return true if attempt is allowed, false if rate-limited.
   */
  public boolean tryConsume(String key) {
    Instant now = Instant.now();
    Window w =
        map.compute(
            key,
            (k, old) -> {
              if (old == null || old.windowStart.plus(window).isBefore(now)) {
                return new Window(now, 1);
              }
              return new Window(old.windowStart, old.attempts + 1);
            });

    if (w.attempts > maxAttempts) {
      return false;
    }

    // best-effort cleanup
    if (map.size() > 10_000) {
      map.entrySet().removeIf(e -> e.getValue().windowStart.plus(window).isBefore(now));
    }
    return true;
  }

  private record Window(Instant windowStart, int attempts) {}
}
