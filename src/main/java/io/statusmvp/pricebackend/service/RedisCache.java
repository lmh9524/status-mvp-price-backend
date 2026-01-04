package io.statusmvp.pricebackend.service;

import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisCache {
  private final StringRedisTemplate redis;

  public RedisCache(StringRedisTemplate redis) {
    this.redis = redis;
  }

  public Optional<String> get(String key) {
    try {
      String v = redis.opsForValue().get(key);
      return Optional.ofNullable(v);
    } catch (Exception ignored) {
      return Optional.empty();
    }
  }

  public void set(String key, String value, long ttlSeconds) {
    if (key == null || value == null) return;
    try {
      redis.opsForValue().set(key, value, Duration.ofSeconds(Math.max(1, ttlSeconds)));
    } catch (Exception ignored) {
      // ignore cache failures
    }
  }
}


