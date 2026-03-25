package io.statusmvp.pricebackend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import io.statusmvp.pricebackend.service.JupiterProxyService;
import jakarta.validation.constraints.NotBlank;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping(path = "/api/v1/solana/jupiter", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
public class JupiterProxyController {
  private static final Set<String> ALLOWED_QUERY_KEYS =
      Set.of(
          "inputMint",
          "outputMint",
          "amount",
          "slippageBps",
          "swapMode",
          "restrictIntermediateTokens",
          "onlyDirectRoutes");

  private final JupiterProxyService jupiter;

  public JupiterProxyController(JupiterProxyService jupiter) {
    this.jupiter = jupiter;
  }

  private static String resolveClientIp(ServerWebExchange exchange) {
    String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      int idx = forwarded.indexOf(',');
      return idx > 0 ? forwarded.substring(0, idx).trim() : forwarded.trim();
    }
    String realIp = exchange.getRequest().getHeaders().getFirst("X-Real-IP");
    if (realIp != null && !realIp.isBlank()) return realIp.trim();
    if (exchange.getRequest().getRemoteAddress() == null) return "";
    if (exchange.getRequest().getRemoteAddress().getAddress() == null) return "";
    return exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
  }

  private static void requireParam(MultiValueMap<String, String> query, @NotBlank String key) {
    String v = query == null ? null : query.getFirst(key);
    if (v == null || v.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing query param: " + key);
    }
  }

  private static MultiValueMap<String, String> filterQuery(MultiValueMap<String, String> query) {
    if (query == null || query.isEmpty()) return query;
    // MultiValueMap is mutable; copy only allowed keys to avoid proxying arbitrary params.
    org.springframework.util.LinkedMultiValueMap<String, String> out = new org.springframework.util.LinkedMultiValueMap<>();
    query.forEach(
        (k, values) -> {
          if (!ALLOWED_QUERY_KEYS.contains(k)) return;
          if (values == null) return;
          for (String v : values) {
            if (v == null) continue;
            out.add(k, v);
          }
        });
    return out;
  }

  @GetMapping("/quote")
  public Mono<ResponseEntity<String>> quote(
      @RequestParam(required = false) MultiValueMap<String, String> query, ServerWebExchange exchange) {
    MultiValueMap<String, String> q = filterQuery(query);
    requireParam(q, "inputMint");
    requireParam(q, "outputMint");
    requireParam(q, "amount");
    return jupiter.quote(q, resolveClientIp(exchange));
  }

  @PostMapping("/swap")
  public Mono<ResponseEntity<String>> swap(
      @RequestBody(required = false) JsonNode body, ServerWebExchange exchange) {
    return jupiter.swap(body, resolveClientIp(exchange));
  }
}

