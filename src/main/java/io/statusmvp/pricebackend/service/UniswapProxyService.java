package io.statusmvp.pricebackend.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class UniswapProxyService {
  private static final Logger log = LoggerFactory.getLogger(UniswapProxyService.class);
  private static final MediaType DEFAULT_CONTENT_TYPE = MediaType.APPLICATION_JSON;

  private record RateLimitDecision(boolean allowed, int retryAfterSeconds) {}

  private final WebClient webClient;
  private final String baseUrl;
  private final String apiKey;
  private final Duration timeout;
  private final StringRedisTemplate redis;
  private final int windowSeconds;
  private final int ipLimit;

  public UniswapProxyService(
      WebClient webClient,
      StringRedisTemplate redis,
      @Value("${app.uniswap.apiBaseUrl:https://trade-api.gateway.uniswap.org/v1}") String baseUrl,
      @Value("${app.uniswap.apiKey:}") String apiKey,
      @Value("${app.uniswap.timeoutMs:12000}") long timeoutMs,
      @Value("${app.uniswap.rateLimitWindowSeconds:60}") int windowSeconds,
      @Value("${app.uniswap.rateLimitIpLimit:600}") int ipLimit) {
    this.webClient = webClient;
    this.redis = redis;
    this.baseUrl = normalizeBaseUrl(baseUrl);
    this.apiKey = apiKey == null ? "" : apiKey.trim();
    this.timeout = Duration.ofMillis(Math.max(1000, timeoutMs));
    this.windowSeconds = Math.max(1, windowSeconds);
    this.ipLimit = Math.max(1, ipLimit);
    log.info(
        "uniswap proxy initialized: baseUrl={}, timeoutMs={}, rateLimitWindowSeconds={}, ipLimit={}, apiKeyConfigured={}",
        this.baseUrl,
        this.timeout.toMillis(),
        this.windowSeconds,
        this.ipLimit,
        !this.apiKey.isBlank());
  }

  private static String normalizeBaseUrl(String value) {
    if (value == null) return "";
    String trimmed = value.trim();
    while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
    return trimmed;
  }

  private static String normalizePath(String value) {
    String trimmed = value == null ? "" : value.trim();
    if (!trimmed.startsWith("/")) return "/" + trimmed;
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
    String value = normalizeKey(ip);
    if (value.isBlank()) return "";
    return "uniswap:proxy:rl:ip:" + value;
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
    } catch (Exception e) {
      log.warn("uniswap proxy rate-limit counter failed: key={}, limit={}", key, limit, e);
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

  private static ResponseEntity<String> upstreamUnavailableResponse() {
    return ResponseEntity.status(502)
        .contentType(DEFAULT_CONTENT_TYPE)
        .body("{\"error\":\"upstream unavailable\"}");
  }

  private URI buildUri(String path) {
    return UriComponentsBuilder.fromUriString(baseUrl + normalizePath(path)).build(true).toUri();
  }

  private Mono<ResponseEntity<String>> post(String path, JsonNode body, String clientIp) {
    String normalizedPath = normalizePath(path);
    if (baseUrl.isBlank()) {
      log.warn("uniswap proxy rejected request: path={}, reason=UNISWAP_API_BASE_URL not set", normalizedPath);
      return Mono.just(
          ResponseEntity.status(503)
              .contentType(DEFAULT_CONTENT_TYPE)
              .body("{\"error\":\"UNISWAP_API_BASE_URL not set\"}"));
    }
    if (apiKey.isBlank()) {
      log.warn("uniswap proxy rejected request: path={}, reason=UNISWAP_API_KEY not set", normalizedPath);
      return Mono.just(
          ResponseEntity.status(503)
              .contentType(DEFAULT_CONTENT_TYPE)
              .body("{\"error\":\"UNISWAP_API_KEY not set\"}"));
    }
    return checkRateLimit(clientIp)
        .flatMap(
            rl -> {
              if (!rl.allowed()) {
                log.warn(
                    "uniswap proxy rate limited: path={}, clientIp={}, retryAfterSeconds={}",
                    normalizedPath,
                    clientIp,
                    rl.retryAfterSeconds());
                return Mono.just(rateLimitedResponse(rl.retryAfterSeconds()));
              }
              URI uri = buildUri(normalizedPath);
              log.info(
                  "uniswap proxy forwarding request: path={}, clientIp={}, uri={}",
                  normalizedPath,
                  clientIp,
                  uri);
              return webClient
                  .post()
                  .uri(uri)
                  .headers(
                      h -> {
                        h.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
                        h.set("x-api-key", apiKey);
                        h.set("x-permit2-enabled", "false");
                        h.set("x-universal-router-version", "2.0");
                      })
                  .bodyValue(body == null ? "{}" : body)
                  .exchangeToMono(
                      resp -> {
                        if (resp.statusCode().isError()) {
                          log.warn(
                              "uniswap proxy upstream returned error: path={}, status={}, clientIp={}",
                              normalizedPath,
                              resp.statusCode().value(),
                              clientIp);
                        }
                        return toResponseEntity(resp);
                      })
                  .timeout(timeout)
                  .onErrorResume(
                      TimeoutException.class,
                      e -> {
                        log.warn(
                            "uniswap proxy upstream timeout: path={}, timeoutMs={}, clientIp={}",
                            normalizedPath,
                            timeout.toMillis(),
                            clientIp,
                            e);
                        return Mono.just(upstreamTimeoutResponse());
                      })
                  .onErrorResume(
                      Exception.class,
                      e -> {
                        log.warn(
                            "uniswap proxy upstream request failed: path={}, clientIp={}",
                            normalizedPath,
                            clientIp,
                            e);
                        return Mono.just(upstreamUnavailableResponse());
                      });
            });
  }

  public Mono<ResponseEntity<String>> checkApproval(JsonNode body, String clientIp) {
    return post("/check_approval", body, clientIp);
  }

  public Mono<ResponseEntity<String>> quote(JsonNode body, String clientIp) {
    return post("/quote", body, clientIp);
  }

  public Mono<ResponseEntity<String>> swap(JsonNode body, String clientIp) {
    return post("/swap", body, clientIp);
  }
}
