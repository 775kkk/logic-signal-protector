package com.logicsignalprotector.apigateway.common.ratelimit;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RedisRateLimitService {

  private final StringRedisTemplate redis;

  @Value("${rate-limits.login.window-seconds:900}")
  private long loginWindowSeconds;

  @Value("${rate-limits.login.max-attempts:10}")
  private long loginMaxAttempts;

  @Value("${rate-limits.register.window-seconds:3600}")
  private long registerWindowSeconds;

  @Value("${rate-limits.register.max-attempts:10}")
  private long registerMaxAttempts;

  public void checkLogin(String login, String ip) {
    String key = "rl:login:" + normalize(login) + ":ip:" + normalize(ip);
    long count = incrementWithTtl(key, Duration.ofSeconds(loginWindowSeconds));
    if (count > loginMaxAttempts) {
      throw new TooManyRequestsException("Too many login attempts. Try again later.");
    }
  }

  public void checkRegister(String login, String ip) {
    String key = "rl:register:" + normalize(login) + ":ip:" + normalize(ip);
    long count = incrementWithTtl(key, Duration.ofSeconds(registerWindowSeconds));
    if (count > registerMaxAttempts) {
      throw new TooManyRequestsException("Too many registration attempts. Try again later.");
    }
  }

  private long incrementWithTtl(String key, Duration ttl) {
    Long val = redis.opsForValue().increment(key);
    if (val != null && val == 1L) {
      redis.expire(key, ttl);
    }
    return val == null ? 0L : val;
  }

  private static String normalize(String s) {
    return s == null ? "unknown" : s.trim().toLowerCase();
  }
}
