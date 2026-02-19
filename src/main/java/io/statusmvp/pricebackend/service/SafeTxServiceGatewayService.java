package io.statusmvp.pricebackend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.statusmvp.pricebackend.client.SafeTxServiceClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class SafeTxServiceGatewayService {
  private static final String CACHE_FRESH_PREFIX = "safe:tx:gw:fresh:";
  private static final String CACHE_STALE_PREFIX = "safe:tx:gw:stale:";
  private static final MediaType DEFAULT_CONTENT_TYPE = MediaType.APPLICATION_JSON;

  public record CachePolicy(long freshTtlSeconds, long staleTtlSeconds, long notFoundTtlSeconds) {}

  private record CachedResponse(int status, String contentType, String body) {}

  private record RateLimitDecision(boolean allowed, int retryAfterSeconds) {}

  private final SafeTxServiceClient safeTxService;
  private final RedisCache cache;
  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;

  private final int windowSeconds;
  private final int ipLimit;
  private final int deviceLimit;

  private final ConcurrentHashMap<String, Mono<ResponseEntity<String>>> inflight = new ConcurrentHashMap<>();

  public SafeTxServiceGatewayService(
      SafeTxServiceClient safeTxService,
      RedisCache cache,
      StringRedisTemplate redis,
      ObjectMapper objectMapper,
      @Value("${SAFE_TX_GW_RL_WINDOW_SECONDS:60}") int windowSeconds,
      @Value("${SAFE_TX_GW_RL_IP_LIMIT:600}") int ipLimit,
      @Value("${SAFE_TX_GW_RL_DEVICE_LIMIT:300}") int deviceLimit) {
    this.safeTxService = safeTxService;
    this.cache = cache;
    this.redis = redis;
    this.objectMapper = objectMapper;
    this.windowSeconds = Math.max(1, windowSeconds);
    this.ipLimit = Math.max(1, ipLimit);
    this.deviceLimit = Math.max(1, deviceLimit);
  }

  public Mono<ResponseEntity<String>> get(
      String chain,
      String path,
      MultiValueMap<String, String> query,
      String clientIp,
      String deviceId,
      CachePolicy policy) {
    CachePolicy p = policy == null ? new CachePolicy(0, 0, 0) : policy;
    String freshKey = cacheKey(CACHE_FRESH_PREFIX, chain, path, query);
    String staleKey = cacheKey(CACHE_STALE_PREFIX, chain, path, query);

    return checkRateLimits(clientIp, deviceId)
        .flatMap(
            rl -> {
              if (!rl.allowed()) {
                return Mono.just(rateLimitedResponse(rl.retryAfterSeconds()));
              }
              return readCache(freshKey)
                  .flatMap(
                      cached -> {
                        if (cached.isPresent()) {
                          return Mono.just(toResponseEntity(cached.get(), false));
                        }
                        return getInflight(freshKey, staleKey, chain, path, query, p);
                      });
            });
  }

  public Mono<ResponseEntity<String>> post(
      String chain,
      String path,
      MultiValueMap<String, String> query,
      JsonNode body,
      String clientIp,
      String deviceId) {
    return checkRateLimits(clientIp, deviceId)
        .flatMap(
            rl -> {
              if (!rl.allowed()) {
                return Mono.just(rateLimitedResponse(rl.retryAfterSeconds()));
              }
              return safeTxService.post(chain, path, query, body);
            });
  }

  private Mono<ResponseEntity<String>> getInflight(
      String freshKey,
      String staleKey,
      String chain,
      String path,
      MultiValueMap<String, String> query,
      CachePolicy policy) {
    return inflight.computeIfAbsent(
        freshKey,
        k ->
            safeTxService
                .get(chain, path, query)
                .flatMap(
                    resp -> {
                      int status = resp.getStatusCode().value();
                      if (status >= 200 && status < 300) {
                        CachedResponse cached = toCachedResponse(resp);
                        return Mono.when(
                                writeCache(freshKey, cached, policy.freshTtlSeconds()),
                                writeCache(staleKey, cached, policy.staleTtlSeconds()))
                            .thenReturn(resp);
                      }

                      if (status == 404 && policy.notFoundTtlSeconds() > 0) {
                        CachedResponse cached = toCachedResponse(resp);
                        return writeCache(freshKey, cached, policy.notFoundTtlSeconds()).thenReturn(resp);
                      }

                      if (status == 429 || status >= 500) {
                        return readCache(staleKey)
                            .map(stale -> stale.map(v -> toResponseEntity(v, true)).orElse(resp));
                      }

                      return Mono.just(resp);
                    })
                .onErrorResume(
                    err ->
                        readCache(staleKey)
                            .flatMap(
                                stale -> {
                                  if (stale.isPresent()) return Mono.just(toResponseEntity(stale.get(), true));
                                  return Mono.error(err);
                                }))
                .doFinally(sig -> inflight.remove(k))
                .cache());
  }

  private Mono<Optional<CachedResponse>> readCache(String key) {
    return Mono.fromCallable(
            () -> {
              Optional<String> raw = cache.get(key);
              if (raw.isEmpty()) return Optional.<CachedResponse>empty();
              try {
                return Optional.of(objectMapper.readValue(raw.get(), CachedResponse.class));
              } catch (Exception ignored) {
                return Optional.<CachedResponse>empty();
              }
            })
        .subscribeOn(Schedulers.boundedElastic());
  }

  private Mono<Void> writeCache(String key, CachedResponse payload, long ttlSeconds) {
    if (ttlSeconds <= 0) return Mono.empty();
    return Mono.fromRunnable(
            () -> {
              try {
                cache.set(key, objectMapper.writeValueAsString(payload), ttlSeconds);
              } catch (Exception ignored) {
                // ignore cache failures
              }
            })
        .subscribeOn(Schedulers.boundedElastic())
        .then();
  }

  private Mono<RateLimitDecision> checkRateLimits(String ip, String deviceId) {
    return Mono.fromCallable(
            () -> {
              int retryAfter = 0;
              if (ip != null && !ip.isBlank()) {
                retryAfter = Math.max(retryAfter, checkLimit("ip", key("ip", normalizeKey(ip)), ipLimit));
              }
              if (deviceId != null && !deviceId.isBlank()) {
                retryAfter =
                    Math.max(
                        retryAfter,
                        checkLimit("device", key("device", normalizeKey(deviceId)), deviceLimit));
              }
              if (retryAfter > 0) return new RateLimitDecision(false, retryAfter);
              return new RateLimitDecision(true, 0);
            })
        .subscribeOn(Schedulers.boundedElastic());
  }

  private int checkLimit(String scope, String key, int limit) {
    if (key == null || key.isBlank()) return 0;
    try {
      Long count = redis.opsForValue().increment(key);
      if (count != null && count == 1L) {
        redis.expire(key, Duration.ofSeconds(windowSeconds));
      }
      if (count != null && count > limit) {
        Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
        int retryAfter = ttl == null || ttl < 1 ? windowSeconds : ttl.intValue();
        return Math.max(1, retryAfter);
      }
      return 0;
    } catch (Exception ignored) {
      return 0;
    }
  }

  private static String key(String prefix, String suffix) {
    if (suffix == null || suffix.isBlank()) return "";
    return "safe:tx:gw:rl:" + prefix + ":" + suffix;
  }

  private static String normalizeKey(String value) {
    if (value == null) return "";
    return value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-:.]", "_");
  }

  private static ResponseEntity<String> rateLimitedResponse(int retryAfterSeconds) {
    int retry = Math.max(1, retryAfterSeconds);
    return ResponseEntity.status(429)
        .contentType(DEFAULT_CONTENT_TYPE)
        .header(HttpHeaders.RETRY_AFTER, String.valueOf(retry))
        .body("{\"error\":\"rate limited\",\"retryAfterSeconds\":" + retry + "}");
  }

  private static CachedResponse toCachedResponse(ResponseEntity<String> resp) {
    MediaType ct = resp.getHeaders().getContentType();
    String contentType = ct == null ? DEFAULT_CONTENT_TYPE.toString() : ct.toString();
    String body = resp.getBody() == null ? "" : resp.getBody();
    return new CachedResponse(resp.getStatusCode().value(), contentType, body);
  }

  private static ResponseEntity<String> toResponseEntity(CachedResponse cached, boolean stale) {
    ResponseEntity.BodyBuilder b = ResponseEntity.status(cached.status());
    try {
      b.contentType(MediaType.parseMediaType(cached.contentType()));
    } catch (Exception ignored) {
      b.contentType(DEFAULT_CONTENT_TYPE);
    }
    b.header("X-Cache", stale ? "STALE" : "HIT");
    return b.body(cached.body() == null ? "" : cached.body());
  }

  private static String cacheKey(
      String prefix, String chain, String path, MultiValueMap<String, String> query) {
    String base = (chain == null ? "" : chain.trim()) + "|" + (path == null ? "" : path.trim()) + "|" + canonicalQuery(query);
    return prefix + sha256Hex(base);
  }

  private static String canonicalQuery(MultiValueMap<String, String> query) {
    if (query == null || query.isEmpty()) return "";
    List<Map.Entry<String, List<String>>> entries = new ArrayList<>(query.entrySet());
    entries.sort(Map.Entry.comparingByKey());
    return entries.stream()
        .flatMap(
            e -> {
              List<String> values = e.getValue() == null ? List.of() : e.getValue();
              if (values.isEmpty()) return java.util.stream.Stream.of(e.getKey() + "=");
              List<String> sorted = new ArrayList<>(values);
              sorted.sort(Comparator.naturalOrder());
              return sorted.stream().map(v -> e.getKey() + "=" + v);
            })
        .collect(Collectors.joining("&"));
  }

  private static String sha256Hex(String input) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest((input == null ? "" : input).getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) sb.append(String.format("%02x", b));
      return sb.toString();
    } catch (Exception e) {
      return Integer.toHexString((input == null ? "" : input).hashCode());
    }
  }
}
