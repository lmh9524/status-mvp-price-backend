package io.statusmvp.pricebackend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import io.statusmvp.pricebackend.client.SafeTxServiceClient;
import jakarta.validation.constraints.NotBlank;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping(path = "/api/v1/safe/tx-service", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
public class SafeTxServiceProxyController {
  private static final Set<String> ALLOWED_CHAINS = Set.of("eth", "base", "oeth", "arb1", "bnb");
  private static final String DISABLED_BODY =
      "{\"error\":\"Safe tx-service proxy disabled; use /api/v1/safe/tx-service-gateway\"}";

  private final SafeTxServiceClient safeTxService;
  private final boolean proxyEnabled;

  public SafeTxServiceProxyController(
      SafeTxServiceClient safeTxService,
      @Value("${SAFE_TX_PROXY_ENABLED:false}") boolean proxyEnabled) {
    this.safeTxService = safeTxService;
    this.proxyEnabled = proxyEnabled;
  }

  private static String normalizeChain(String chain) {
    String c = chain == null ? "" : chain.trim().toLowerCase();
    if (!ALLOWED_CHAINS.contains(c)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported chain: " + chain);
    }
    return c;
  }

  private Mono<ResponseEntity<String>> whenEnabled(Supplier<Mono<ResponseEntity<String>>> action) {
    if (!proxyEnabled) {
      return Mono.just(
          ResponseEntity.status(HttpStatus.GONE)
              .contentType(MediaType.APPLICATION_JSON)
              .body(DISABLED_BODY));
    }
    return action.get();
  }

  @GetMapping("/{chain}/safes/{address}")
  public Mono<ResponseEntity<String>> getSafeInfo(
      @PathVariable("chain") String chain,
      @PathVariable("address") @NotBlank String address) {
    return whenEnabled(
        () -> {
          String c = normalizeChain(chain);
          return safeTxService.get(c, "/api/v1/safes/" + address + "/", null);
        });
  }

  @GetMapping("/{chain}/safes/{address}/multisig-transactions")
  public Mono<ResponseEntity<String>> listMultisigTransactions(
      @PathVariable("chain") String chain,
      @PathVariable("address") @NotBlank String address,
      @RequestParam(value = "executed", required = false) Boolean executed,
      @RequestParam(value = "ordering", required = false) String ordering,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "offset", required = false) Integer offset) {
    return whenEnabled(
        () -> {
          String c = normalizeChain(chain);
          MultiValueMap<String, String> q = new LinkedMultiValueMap<>();
          if (executed != null) q.add("executed", executed.toString());
          if (ordering != null && !ordering.isBlank()) q.add("ordering", ordering);
          if (limit != null) q.add("limit", String.valueOf(limit));
          if (offset != null) q.add("offset", String.valueOf(offset));
          return safeTxService.get(c, "/api/v2/safes/" + address + "/multisig-transactions/", q);
        });
  }

  @GetMapping("/{chain}/multisig-transactions/{safeTxHash}")
  public Mono<ResponseEntity<String>> getMultisigTransaction(
      @PathVariable("chain") String chain,
      @PathVariable("safeTxHash") @NotBlank String safeTxHash) {
    return whenEnabled(
        () -> {
          String c = normalizeChain(chain);
          return safeTxService.get(c, "/api/v2/multisig-transactions/" + safeTxHash + "/", null);
        });
  }

  @GetMapping("/{chain}/multisig-transactions/{safeTxHash}/confirmations")
  public Mono<ResponseEntity<String>> listConfirmations(
      @PathVariable("chain") String chain,
      @PathVariable("safeTxHash") @NotBlank String safeTxHash,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "offset", required = false) Integer offset) {
    return whenEnabled(
        () -> {
          String c = normalizeChain(chain);
          MultiValueMap<String, String> q = new LinkedMultiValueMap<>();
          if (limit != null) q.add("limit", String.valueOf(limit));
          if (offset != null) q.add("offset", String.valueOf(offset));
          return safeTxService.get(c, "/api/v1/multisig-transactions/" + safeTxHash + "/confirmations/", q);
        });
  }

  @PostMapping("/{chain}/safes/{address}/multisig-transactions")
  public Mono<ResponseEntity<String>> proposeMultisigTransaction(
      @PathVariable("chain") String chain,
      @PathVariable("address") @NotBlank String address,
      @RequestBody(required = false) JsonNode body) {
    return whenEnabled(
        () -> {
          String c = normalizeChain(chain);
          return safeTxService.post(c, "/api/v2/safes/" + address + "/multisig-transactions/", null, body);
        });
  }

  @PostMapping("/{chain}/multisig-transactions/{safeTxHash}/confirmations")
  public Mono<ResponseEntity<String>> confirmMultisigTransaction(
      @PathVariable("chain") String chain,
      @PathVariable("safeTxHash") @NotBlank String safeTxHash,
      @RequestBody(required = false) JsonNode body) {
    return whenEnabled(
        () -> {
          String c = normalizeChain(chain);
          return safeTxService.post(
              c, "/api/v1/multisig-transactions/" + safeTxHash + "/confirmations/", null, body);
        });
  }
}

