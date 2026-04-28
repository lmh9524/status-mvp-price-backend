package io.statusmvp.pricebackend.controller;

import io.statusmvp.pricebackend.auth.AuthUtils;
import io.statusmvp.pricebackend.model.token.TokenSearchResponse;
import io.statusmvp.pricebackend.service.TokenCatalogService;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping(path = "/api/v1/tokens", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
public class TokenSearchController {
  private record RateLimitDecision(boolean allowed, int retryAfterSeconds) {}

  private final TokenCatalogService tokens;
  private final StringRedisTemplate redis;
  private final int windowSeconds;
  private final int ipLimit;

  public TokenSearchController(
      TokenCatalogService tokens,
      StringRedisTemplate redis,
      @Value("${app.tokenCatalog.rateLimitWindowSeconds:60}") int windowSeconds,
      @Value("${app.tokenCatalog.rateLimitIpLimit:600}") int ipLimit) {
    this.tokens = tokens;
    this.redis = redis;
    this.windowSeconds = Math.max(1, windowSeconds);
    this.ipLimit = Math.max(1, ipLimit);
  }

  @GetMapping("/search")
  public Mono<ResponseEntity<TokenSearchResponse>> search(
      @RequestParam("chainId") int chainId,
      @RequestParam(value = "q", required = false, defaultValue = "") String query,
      @RequestParam(value = "limit", required = false, defaultValue = "30") int limit,
      ServerWebExchange exchange) {
    requireEnabledAndAllowed(chainId);
    return checkRateLimit(resolveClientIp(exchange))
        .flatMap(
            rl -> {
              if (!rl.allowed()) return Mono.just(rateLimitedResponse(rl.retryAfterSeconds()));
              return Mono.fromCallable(() -> ResponseEntity.ok(tokens.search(chainId, query, limit)))
                  .subscribeOn(Schedulers.boundedElastic());
            });
  }

  @GetMapping("/lookup")
  public Mono<ResponseEntity<TokenSearchResponse>> lookup(
      @RequestParam("chainId") int chainId,
      @RequestParam("address") @NotBlank String address,
      ServerWebExchange exchange) {
    requireEnabledAndAllowed(chainId);
    return checkRateLimit(resolveClientIp(exchange))
        .flatMap(
            rl -> {
              if (!rl.allowed()) return Mono.just(rateLimitedResponse(rl.retryAfterSeconds()));
              return Mono.fromCallable(() -> ResponseEntity.ok(tokens.lookup(chainId, address)))
                  .subscribeOn(Schedulers.boundedElastic());
            });
  }

  private void requireEnabledAndAllowed(int chainId) {
    if (!tokens.isEnabled()) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "token catalog disabled");
    }
    if (!tokens.isAllowedChainId(chainId)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported chainId: " + chainId);
    }
  }

  private static String resolveClientIp(ServerWebExchange exchange) {
    String forwarded =
        AuthUtils.firstForwardedValue(exchange.getRequest().getHeaders().getFirst("X-Forwarded-For"));
    if (!forwarded.isBlank()) return forwarded;
    String realIp =
        AuthUtils.firstForwardedValue(exchange.getRequest().getHeaders().getFirst("X-Real-IP"));
    if (!realIp.isBlank()) return realIp;
    if (exchange.getRequest().getRemoteAddress() == null) return "";
    if (exchange.getRequest().getRemoteAddress().getAddress() == null) return "";
    return exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
  }

  private static String normalizeKey(String value) {
    if (value == null) return "";
    return value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-:.]", "_");
  }

  private static String rlKey(String ip) {
    String value = normalizeKey(ip);
    return value.isBlank() ? "" : "token-catalog:rl:ip:" + value;
  }

  private Mono<RateLimitDecision> checkRateLimit(String ip) {
    return Mono.fromCallable(
            () -> {
              String key = rlKey(ip);
              if (key.isBlank()) return new RateLimitDecision(true, 0);
              try {
                Long count = redis.opsForValue().increment(key);
                if (count != null && count == 1L) redis.expire(key, Duration.ofSeconds(windowSeconds));
                if (count != null && count > ipLimit) {
                  Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
                  int retryAfter = ttl == null || ttl < 1 ? windowSeconds : ttl.intValue();
                  return new RateLimitDecision(false, retryAfter);
                }
              } catch (Exception ignored) {
                // Fail open if Redis is unavailable.
              }
              return new RateLimitDecision(true, 0);
            })
        .subscribeOn(Schedulers.boundedElastic());
  }

  private static ResponseEntity<TokenSearchResponse> rateLimitedResponse(int retryAfterSeconds) {
    int retry = Math.max(1, retryAfterSeconds);
    return ResponseEntity.status(429)
        .header(HttpHeaders.RETRY_AFTER, String.valueOf(retry))
        .build();
  }
}
