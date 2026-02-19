package io.statusmvp.pricebackend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import io.statusmvp.pricebackend.service.SafeTxServiceGatewayService;
import jakarta.validation.constraints.NotBlank;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping(path = "/api/v1/safe/tx-service-gateway", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
public class SafeTxServiceGatewayController {
  private static final Set<String> ALLOWED_CHAINS = Set.of("eth", "base", "oeth", "arb1", "bnb");

  private static final SafeTxServiceGatewayService.CachePolicy SAFE_INFO_CACHE =
      new SafeTxServiceGatewayService.CachePolicy(10, 600, 3);
  private static final SafeTxServiceGatewayService.CachePolicy TX_LIST_CACHE =
      new SafeTxServiceGatewayService.CachePolicy(5, 120, 2);
  private static final SafeTxServiceGatewayService.CachePolicy TX_DETAIL_CACHE =
      new SafeTxServiceGatewayService.CachePolicy(10, 600, 2);
  private static final SafeTxServiceGatewayService.CachePolicy CONFIRMATIONS_CACHE =
      new SafeTxServiceGatewayService.CachePolicy(5, 120, 2);

  private final SafeTxServiceGatewayService gateway;

  public SafeTxServiceGatewayController(SafeTxServiceGatewayService gateway) {
    this.gateway = gateway;
  }

  private static String normalizeChain(String chain) {
    String c = chain == null ? "" : chain.trim().toLowerCase();
    if (!ALLOWED_CHAINS.contains(c)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported chain: " + chain);
    }
    return c;
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

  private static Mono<ResponseEntity<String>> missingDeviceId() {
    return Mono.just(
        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .contentType(MediaType.APPLICATION_JSON)
            .body("{\"error\":\"Missing X-Device-Id\"}"));
  }

  @GetMapping({"/{chain}/api/v1/safes/{address}", "/{chain}/api/v1/safes/{address}/"})
  public Mono<ResponseEntity<String>> getSafeInfo(
      @PathVariable("chain") String chain,
      @PathVariable("address") @NotBlank String address,
      @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
      ServerWebExchange exchange) {
    if (deviceId == null || deviceId.isBlank()) return missingDeviceId();
    String c = normalizeChain(chain);
    return gateway.get(
        c, "/api/v1/safes/" + address + "/", null, resolveClientIp(exchange), deviceId, SAFE_INFO_CACHE);
  }

  @GetMapping({"/{chain}/api/v2/safes/{address}/multisig-transactions", "/{chain}/api/v2/safes/{address}/multisig-transactions/"})
  public Mono<ResponseEntity<String>> listMultisigTransactions(
      @PathVariable("chain") String chain,
      @PathVariable("address") @NotBlank String address,
      @RequestParam(required = false) MultiValueMap<String, String> query,
      @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
      ServerWebExchange exchange) {
    if (deviceId == null || deviceId.isBlank()) return missingDeviceId();
    String c = normalizeChain(chain);
    return gateway.get(
        c,
        "/api/v2/safes/" + address + "/multisig-transactions/",
        query,
        resolveClientIp(exchange),
        deviceId,
        TX_LIST_CACHE);
  }

  @GetMapping({"/{chain}/api/v2/multisig-transactions/{safeTxHash}", "/{chain}/api/v2/multisig-transactions/{safeTxHash}/"})
  public Mono<ResponseEntity<String>> getMultisigTransaction(
      @PathVariable("chain") String chain,
      @PathVariable("safeTxHash") @NotBlank String safeTxHash,
      @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
      ServerWebExchange exchange) {
    if (deviceId == null || deviceId.isBlank()) return missingDeviceId();
    String c = normalizeChain(chain);
    return gateway.get(
        c,
        "/api/v2/multisig-transactions/" + safeTxHash + "/",
        null,
        resolveClientIp(exchange),
        deviceId,
        TX_DETAIL_CACHE);
  }

  @GetMapping({"/{chain}/api/v1/multisig-transactions/{safeTxHash}/confirmations", "/{chain}/api/v1/multisig-transactions/{safeTxHash}/confirmations/"})
  public Mono<ResponseEntity<String>> listConfirmations(
      @PathVariable("chain") String chain,
      @PathVariable("safeTxHash") @NotBlank String safeTxHash,
      @RequestParam(required = false) MultiValueMap<String, String> query,
      @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
      ServerWebExchange exchange) {
    if (deviceId == null || deviceId.isBlank()) return missingDeviceId();
    String c = normalizeChain(chain);
    return gateway.get(
        c,
        "/api/v1/multisig-transactions/" + safeTxHash + "/confirmations/",
        query,
        resolveClientIp(exchange),
        deviceId,
        CONFIRMATIONS_CACHE);
  }

  @PostMapping({"/{chain}/api/v2/safes/{address}/multisig-transactions", "/{chain}/api/v2/safes/{address}/multisig-transactions/"})
  public Mono<ResponseEntity<String>> proposeMultisigTransaction(
      @PathVariable("chain") String chain,
      @PathVariable("address") @NotBlank String address,
      @RequestBody(required = false) JsonNode body,
      @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
      ServerWebExchange exchange) {
    if (deviceId == null || deviceId.isBlank()) return missingDeviceId();
    String c = normalizeChain(chain);
    return gateway.post(
        c,
        "/api/v2/safes/" + address + "/multisig-transactions/",
        null,
        body,
        resolveClientIp(exchange),
        deviceId);
  }

  @PostMapping({"/{chain}/api/v1/multisig-transactions/{safeTxHash}/confirmations", "/{chain}/api/v1/multisig-transactions/{safeTxHash}/confirmations/"})
  public Mono<ResponseEntity<String>> confirmMultisigTransaction(
      @PathVariable("chain") String chain,
      @PathVariable("safeTxHash") @NotBlank String safeTxHash,
      @RequestBody(required = false) JsonNode body,
      @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
      ServerWebExchange exchange) {
    if (deviceId == null || deviceId.isBlank()) return missingDeviceId();
    String c = normalizeChain(chain);
    return gateway.post(
        c,
        "/api/v1/multisig-transactions/" + safeTxHash + "/confirmations/",
        null,
        body,
        resolveClientIp(exchange),
        deviceId);
  }
}

