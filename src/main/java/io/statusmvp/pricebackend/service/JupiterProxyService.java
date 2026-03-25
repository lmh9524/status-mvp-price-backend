package io.statusmvp.pricebackend.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class JupiterProxyService {
  private static final MediaType DEFAULT_CONTENT_TYPE = MediaType.APPLICATION_JSON;

  private record RateLimitDecision(boolean allowed, int retryAfterSeconds) {}

  private final WebClient webClient;
  private final String baseUrl;
  private final Duration timeout;
  private final StringRedisTemplate redis;

  private final int windowSeconds;
  private final int ipLimit;

  public JupiterProxyService(
      WebClient webClient,
      StringRedisTemplate redis,
      @Value("${JUPITER_LITE_API_BASE_URL:https://lite-api.jup.ag/swap/v1}") String baseUrl,
      @Value("${JUPITER_TIMEOUT_MS:12000}") long timeoutMs,
      @Value("${JUPITER_RL_WINDOW_SECONDS:60}") int windowSeconds,
      @Value("${JUPITER_RL_IP_LIMIT:600}") int ipLimit) {
    this.webClient = webClient;
    this.redis = redis;
    this.baseUrl = normalizeBaseUrl(baseUrl);
    this.timeout = Duration.ofMillis(Math.max(1000, timeoutMs));
    this.windowSeconds = Math.max(1, windowSeconds);
    this.ipLimit = Math.max(1, ipLimit);
  }

  private static String normalizeBaseUrl(String value) {
    if (value == null) return "";
    String trimmed = value.trim();
    while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
    return trimmed;
  }

  private static MediaType responseContentType(ClientResponse resp) {
    return resp.headers().contentType().orElse(DEFAULT_CONTENT_TYPE);
  }

  private static Mono<ResponseEntity<String>> toResponseEntity(ClientResponse resp) {
    return resp
        .bodyToMono(String.class)
        .defaultIfEmpty("")
        .map(
            body ->
                ResponseEntity.status(resp.statusCode())
                    .contentType(responseContentType(resp))
                    .body(body));
  }

  private static String normalizeKey(String value) {
    if (value == null) return "";
    return value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-:.]", "_");
  }

  private static String rlKey(String ip) {
    String v = normalizeKey(ip);
    if (v.isBlank()) return "";
    return "jupiter:proxy:rl:ip:" + v;
  }

  private int checkLimit(String key, int limit) {
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

  private Mono<RateLimitDecision> checkRateLimit(String ip) {
    return Mono.fromCallable(
            () -> {
              if (ip == null || ip.isBlank()) return new RateLimitDecision(true, 0);
              int retryAfter = checkLimit(rlKey(ip), ipLimit);
              if (retryAfter > 0) return new RateLimitDecision(false, retryAfter);
              return new RateLimitDecision(true, 0);
            })
        .subscribeOn(Schedulers.boundedElastic());
  }

  private static ResponseEntity<String> rateLimitedResponse(int retryAfterSeconds) {
    int retry = Math.max(1, retryAfterSeconds);
    return ResponseEntity.status(429)
        .contentType(DEFAULT_CONTENT_TYPE)
        .header(HttpHeaders.RETRY_AFTER, String.valueOf(retry))
        .body("{\"error\":\"rate limited\",\"retryAfterSeconds\":" + retry + "}");
  }

  private static ResponseEntity<String> upstreamTimeoutResponse() {
    return ResponseEntity.status(504)
        .contentType(DEFAULT_CONTENT_TYPE)
        .body("{\"error\":\"upstream timeout\"}");
  }

  private URI buildUri(String path, MultiValueMap<String, String> query) {
    String p = path == null ? "" : path.trim();
    if (!p.startsWith("/")) p = "/" + p;
    UriComponentsBuilder b = UriComponentsBuilder.fromUriString(baseUrl + p);
    if (query != null && !query.isEmpty()) {
      b.queryParams(query);
    }
    return b.build(true).toUri();
  }

  public Mono<ResponseEntity<String>> quote(MultiValueMap<String, String> query, String clientIp) {
    if (baseUrl.isBlank()) {
      return Mono.just(
          ResponseEntity.status(503).body("{\"error\":\"JUPITER_LITE_API_BASE_URL not set\"}"));
    }
    return checkRateLimit(clientIp)
        .flatMap(
            rl -> {
              if (!rl.allowed()) return Mono.just(rateLimitedResponse(rl.retryAfterSeconds()));
              URI uri = buildUri("/quote", query);
              return webClient
                  .get()
                  .uri(uri)
                  .exchangeToMono(JupiterProxyService::toResponseEntity)
                  .timeout(timeout)
                  .onErrorResume(e -> Mono.just(upstreamTimeoutResponse()));
            });
  }

  public Mono<ResponseEntity<String>> swap(JsonNode body, String clientIp) {
    if (baseUrl.isBlank()) {
      return Mono.just(
          ResponseEntity.status(503).body("{\"error\":\"JUPITER_LITE_API_BASE_URL not set\"}"));
    }
    return checkRateLimit(clientIp)
        .flatMap(
            rl -> {
              if (!rl.allowed()) return Mono.just(rateLimitedResponse(rl.retryAfterSeconds()));
              URI uri = buildUri("/swap", null);
              return webClient
                  .post()
                  .uri(uri)
                  .headers(h -> h.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
                  .bodyValue(body == null ? "{}" : body)
                  .exchangeToMono(JupiterProxyService::toResponseEntity)
                  .timeout(timeout)
                  .onErrorResume(e -> Mono.just(upstreamTimeoutResponse()));
            });
  }
}

