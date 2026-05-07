package io.statusmvp.pricebackend.controller;

import io.statusmvp.pricebackend.auth.AuthUtils;
import io.statusmvp.pricebackend.service.OpenOceanProxyService;
import jakarta.validation.constraints.NotBlank;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping(path = "/api/v1/evm/openocean", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
public class OpenOceanProxyController {
  private static final Set<Integer> ALLOWED_CHAIN_IDS = Set.of(1, 10, 56, 137, 42161, 8453);
  private static final Set<String> ALLOWED_QUERY_KEYS =
      Set.of(
          "inTokenAddress",
          "outTokenAddress",
          "amount",
          "gasPrice",
          "slippage",
          "account");

  private final OpenOceanProxyService openOcean;

  public OpenOceanProxyController(OpenOceanProxyService openOcean) {
    this.openOcean = openOcean;
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

  private static void requireSupportedChainId(int chainId) {
    if (!ALLOWED_CHAIN_IDS.contains(chainId)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Unsupported OpenOcean chainId: " + chainId);
    }
  }

  private static void requireParam(MultiValueMap<String, String> query, @NotBlank String key) {
    String value = query == null ? null : query.getFirst(key);
    if (value == null || value.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing query param: " + key);
    }
  }

  private static MultiValueMap<String, String> filterQuery(MultiValueMap<String, String> query) {
    LinkedMultiValueMap<String, String> out = new LinkedMultiValueMap<>();
    if (query == null || query.isEmpty()) return out;
    query.forEach(
        (key, values) -> {
          if (!ALLOWED_QUERY_KEYS.contains(key) || values == null) return;
          for (String value : values) {
            if (value != null && !value.isBlank()) {
              out.add(key, value);
            }
          }
        });
    return out;
  }

  private static MultiValueMap<String, String> sanitizeQuoteQuery(
      int chainId, MultiValueMap<String, String> query) {
    requireSupportedChainId(chainId);
    MultiValueMap<String, String> out = filterQuery(query);
    requireParam(out, "inTokenAddress");
    requireParam(out, "outTokenAddress");
    requireParam(out, "amount");
    requireParam(out, "gasPrice");
    return out;
  }

  private static MultiValueMap<String, String> sanitizeSwapQuery(
      int chainId, MultiValueMap<String, String> query) {
    MultiValueMap<String, String> out = sanitizeQuoteQuery(chainId, query);
    requireParam(out, "slippage");
    requireParam(out, "account");
    return out;
  }

  @GetMapping("/{chainId}/quote")
  public Mono<ResponseEntity<String>> quote(
      @PathVariable int chainId,
      @RequestParam(required = false) MultiValueMap<String, String> query,
      ServerWebExchange exchange) {
    return openOcean.quote(chainId, sanitizeQuoteQuery(chainId, query), resolveClientIp(exchange));
  }

  @GetMapping("/{chainId}/swap_quote")
  public Mono<ResponseEntity<String>> swapQuote(
      @PathVariable int chainId,
      @RequestParam(required = false) MultiValueMap<String, String> query,
      ServerWebExchange exchange) {
    return openOcean.swapQuote(chainId, sanitizeSwapQuery(chainId, query), resolveClientIp(exchange));
  }
}
