package io.statusmvp.pricebackend.service;

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
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class OpenOceanProxyService {
  private static final Logger log = LoggerFactory.getLogger(OpenOceanProxyService.class);
  private static final MediaType DEFAULT_CONTENT_TYPE = MediaType.APPLICATION_JSON;

  private record RateLimitDecision(boolean allowed, int retryAfterSeconds) {}

  private final WebClient webClient;
  private final StringRedisTemplate redis;
  private final String baseUrl;
  private final Duration timeout;
  private final int windowSeconds;
  private final int ipLimit;

  public OpenOceanProxyService(
      WebClient webClient,
      StringRedisTemplate redis,
      @Value("${app.openOcean.apiBaseUrl:https://open-api.openocean.finance/v3}") String baseUrl,
      @Value("${app.openOcean.timeoutMs:12000}") long timeoutMs,
      @Value("${app.openOcean.rateLimitWindowSeconds:60}") int windowSeconds,
      @Value("${app.openOcean.rateLimitIpLimit:600}") int ipLimit) {
    this.webClient = webClient;
    this.redis = redis;
    this.baseUrl = normalizeBaseUrl(baseUrl);
    this.timeout = Duration.ofMillis(Math.max(1000, timeoutMs));
    this.windowSeconds = Math.max(1, windowSeconds);
    this.ipLimit = Math.max(1, ipLimit);
    log.info(
        "openocean proxy initialized: baseUrl={}, timeoutMs={}, rateLimitWindowSeconds={}, ipLimit={}",
        this.baseUrl,
        this.timeout.toMillis(),
        this.windowSeconds,
        this.ipLimit);
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
    String value = normalizeKey(ip);
    if (value.isBlank()) return "";
    return "openocean:proxy:rl:ip:" + value;
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
      log.warn("openocean proxy rate-limit counter failed: key={}, limit={}", key, limit, e);
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

  private URI buildUri(int chainId, String path, MultiValueMap<String, String> query) {
    String normalizedPath = path.startsWith("/") ? path : "/" + path;
    UriComponentsBuilder builder =
        UriComponentsBuilder.fromUriString(baseUrl + "/" + chainId + normalizedPath);
    if (query != null && !query.isEmpty()) {
      builder.queryParams(query);
    }
    return builder.build(true).toUri();
  }

  private Mono<ResponseEntity<String>> get(
      int chainId, String path, MultiValueMap<String, String> query, String clientIp) {
    if (baseUrl.isBlank()) {
      return Mono.just(
          ResponseEntity.status(503)
              .contentType(DEFAULT_CONTENT_TYPE)
              .body("{\"error\":\"OPENOCEAN_API_BASE_URL not set\"}"));
    }
    return checkRateLimit(clientIp)
        .flatMap(
            rl -> {
              if (!rl.allowed()) {
                log.warn(
                    "openocean proxy rate limited: chainId={}, path={}, clientIp={}, retryAfterSeconds={}",
                    chainId,
                    path,
                    clientIp,
                    rl.retryAfterSeconds());
                return Mono.just(rateLimitedResponse(rl.retryAfterSeconds()));
              }
              URI uri = buildUri(chainId, path, query);
              return webClient
                  .get()
                  .uri(uri)
                  .headers(
                      h -> {
                        h.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
                        h.set(HttpHeaders.USER_AGENT, "VeilWallet-Backend/1.0");
                      })
                  .exchangeToMono(
                      resp -> {
                        if (resp.statusCode().isError()) {
                          log.warn(
                              "openocean proxy upstream returned error: chainId={}, path={}, status={}, clientIp={}",
                              chainId,
                              path,
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
                            "openocean proxy upstream timeout: chainId={}, path={}, timeoutMs={}, clientIp={}",
                            chainId,
                            path,
                            timeout.toMillis(),
                            clientIp,
                            e);
                        return Mono.just(upstreamTimeoutResponse());
                      })
                  .onErrorResume(
                      Exception.class,
                      e -> {
                        log.warn(
                            "openocean proxy upstream request failed: chainId={}, path={}, clientIp={}",
                            chainId,
                            path,
                            clientIp,
                            e);
                        return Mono.just(upstreamUnavailableResponse());
                      });
            });
  }

  public Mono<ResponseEntity<String>> quote(
      int chainId, MultiValueMap<String, String> query, String clientIp) {
    return get(chainId, "/quote", query, clientIp);
  }

  public Mono<ResponseEntity<String>> swapQuote(
      int chainId, MultiValueMap<String, String> query, String clientIp) {
    return get(chainId, "/swap_quote", query, clientIp);
  }
}
