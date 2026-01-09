package com.logicsignalprotector.commandcenter.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Step 1.3: minimal stateful chat store (in-memory with TTL).
 *
 * <p>For production and horizontal scaling, move this to Redis.
 */
@Service
public class ChatStateStore {

  private final ConcurrentMap<String, Entry> map = new ConcurrentHashMap<>();
  private final Duration ttl;

  public ChatStateStore(@Value("${chat.state.ttl:PT10M}") Duration ttl) {
    this.ttl = ttl;
  }

  public Optional<StateEntry> get(String key) {
    Entry e = map.get(key);
    if (e == null) {
      return Optional.empty();
    }
    if (e.expiresAt.isBefore(Instant.now())) {
      map.remove(key);
      return Optional.empty();
    }
    return Optional.of(new StateEntry(e.state, e.payload));
  }

  public void set(String key, ChatState state) {
    set(key, state, null, null);
  }

  /**
   * Set state with optional TTL override.
   *
   * <p>Used in step 1.4 for short-lived confirmations (logout yes...)
   */
  public void set(String key, ChatState state, Duration ttlOverride) {
    set(key, state, null, ttlOverride);
  }

  public void set(String key, ChatState state, String payload, Duration ttlOverride) {
    if (state == null || state == ChatState.NONE) {
      map.remove(key);
      return;
    }
    Duration actual = ttlOverride == null ? ttl : ttlOverride;
    map.put(key, new Entry(state, payload, Instant.now().plus(actual)));
  }

  public void clear(String key) {
    map.remove(key);
  }

  public record StateEntry(ChatState state, String payload) {}

  private record Entry(ChatState state, String payload, Instant expiresAt) {}
}
