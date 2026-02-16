package io.statusmvp.pricebackend.auth;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class AuthRiskService {
  private final StringRedisTemplate redis;
  private final AuthProperties authProperties;
  private final AuthMetrics metrics;

  public AuthRiskService(
      StringRedisTemplate redis, AuthProperties authProperties, AuthMetrics metrics) {
    this.redis = redis;
    this.authProperties = authProperties;
    this.metrics = metrics;
  }

  public void checkIpAllowed(String ip) {
    if (ip == null || ip.isBlank()) return;
    List<String> denylist = authProperties.getRisk().blacklistIpList();
    if (denylist.stream().anyMatch(ip::equalsIgnoreCase)) {
      throw new AuthException(AuthErrorCode.FORBIDDEN, "ip blocked", 403);
    }
  }

  public void checkProviderAllowed(String providerSub) {
    if (providerSub == null || providerSub.isBlank()) return;
    List<String> denylist = authProperties.getRisk().blacklistProviderSubList();
    String normalized = providerSub.toLowerCase(Locale.ROOT);
    if (denylist.stream().map(s -> s.toLowerCase(Locale.ROOT)).anyMatch(normalized::equals)) {
      throw new AuthException(AuthErrorCode.FORBIDDEN, "provider blocked", 403);
    }
  }

  public void checkLoginRateLimits(String ip, String deviceId) {
    int windowSeconds = Math.max(1, authProperties.getRisk().getWindowSeconds());
    checkLimit(
        "login-ip",
        key("login:ip", normalizeKey(ip)),
        Math.max(1, authProperties.getRisk().getLoginIpLimit()),
        windowSeconds);
    if (deviceId != null && !deviceId.isBlank()) {
      checkLimit(
          "login-device",
          key("login:device", normalizeKey(deviceId)),
          Math.max(1, authProperties.getRisk().getLoginDeviceLimit()),
          windowSeconds);
    }
  }

  public void checkBindRateLimit(String walletSub) {
    int windowSeconds = Math.max(1, authProperties.getRisk().getWindowSeconds());
    checkLimit(
        "bind-account",
        key("bind:wallet", normalizeKey(walletSub)),
        Math.max(1, authProperties.getRisk().getBindAccountLimit()),
        windowSeconds);
  }

  private void checkLimit(String scope, String key, int limit, int windowSeconds) {
    if (key == null || key.isBlank()) return;
    Long count = redis.opsForValue().increment(key);
    if (count != null && count == 1L) {
      redis.expire(key, Duration.ofSeconds(windowSeconds));
    }
    if (count != null && count > limit) {
      Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
      int retryAfter = ttl == null || ttl < 1 ? windowSeconds : ttl.intValue();
      metrics.rateLimited(scope);
      throw new AuthException(AuthErrorCode.RATE_LIMITED, "rate limited", 429, retryAfter, java.util.Map.of("scope", scope));
    }
  }

  private static String key(String prefix, String suffix) {
    if (suffix == null || suffix.isBlank()) return "";
    return "auth:rl:" + prefix + ":" + suffix;
  }

  private static String normalizeKey(String value) {
    if (value == null) return "";
    return value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-:.]", "_");
  }
}

