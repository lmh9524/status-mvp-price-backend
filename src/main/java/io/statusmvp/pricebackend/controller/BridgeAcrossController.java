package io.statusmvp.pricebackend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.statusmvp.pricebackend.model.bridge.BridgeAcrossDirectoryResponse;
import io.statusmvp.pricebackend.service.AcrossBridgeDirectoryService;
import io.statusmvp.pricebackend.service.AcrossSwapProxyService;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping(path = "/api/v1/bridge/across", produces = MediaType.APPLICATION_JSON_VALUE)
public class BridgeAcrossController {
  private static final Set<String> ALLOWED_TRADE_TYPES =
      Set.of("exactInput", "minOutput", "exactOutput");

  private final AcrossBridgeDirectoryService across;
  private final AcrossSwapProxyService swap;

  public BridgeAcrossController(AcrossBridgeDirectoryService across, AcrossSwapProxyService swap) {
    this.across = across;
    this.swap = swap;
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static JsonNode requireObjectBody(JsonNode body) {
    if (body == null || body.isNull() || !body.isObject()) {
      throw badRequest("Missing JSON body");
    }
    return body;
  }

  private static String requireText(JsonNode body, String fieldName) {
    String value = body.path(fieldName).asText("").trim();
    if (value.isBlank()) {
      throw badRequest("Missing field: " + fieldName);
    }
    return value;
  }

  private static long requirePositiveChainId(JsonNode body, String fieldName) {
    JsonNode node = body.get(fieldName);
    if (node == null || !node.canConvertToLong()) {
      throw badRequest("Missing field: " + fieldName);
    }
    long value = node.longValue();
    if (value <= 0) {
      throw badRequest("Invalid chain id field: " + fieldName);
    }
    return value;
  }

  private static String requirePositiveIntegerString(JsonNode body, String fieldName) {
    String value = requireText(body, fieldName);
    if (!value.matches("^\\d+$") || "0".equals(value)) {
      throw badRequest("Invalid numeric field: " + fieldName);
    }
    return value;
  }

  private static String sanitizeTradeType(JsonNode body) {
    String raw = body.path("tradeType").asText("exactInput").trim();
    if (raw.isBlank()) return "exactInput";
    String normalized = raw.substring(0, 1).toLowerCase(Locale.ROOT) + raw.substring(1);
    if (!ALLOWED_TRADE_TYPES.contains(normalized)) {
      throw badRequest("Unsupported tradeType: " + raw);
    }
    return normalized;
  }

  private static void copyTextIfPresent(JsonNode body, ObjectNode out, String fieldName) {
    String value = body.path(fieldName).asText("").trim();
    if (!value.isBlank()) out.put(fieldName, value);
  }

  private static void copyBooleanIfPresent(JsonNode body, ObjectNode out, String fieldName) {
    JsonNode node = body.get(fieldName);
    if (node != null && node.isBoolean()) out.put(fieldName, node.booleanValue());
  }

  private static ObjectNode sanitizeSwapApprovalBody(JsonNode body) {
    JsonNode request = requireObjectBody(body);
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    out.put("tradeType", sanitizeTradeType(request));
    out.put("amount", requirePositiveIntegerString(request, "amount"));
    out.put("inputToken", requireText(request, "inputToken"));
    out.put("outputToken", requireText(request, "outputToken"));
    out.put("originChainId", requirePositiveChainId(request, "originChainId"));
    out.put("destinationChainId", requirePositiveChainId(request, "destinationChainId"));
    out.put("depositor", requireText(request, "depositor"));
    copyTextIfPresent(request, out, "recipient");
    copyTextIfPresent(request, out, "refundAddress");
    copyTextIfPresent(request, out, "slippage");
    copyBooleanIfPresent(request, out, "refundOnOrigin");
    copyBooleanIfPresent(request, out, "skipOriginTxEstimation");
    return out;
  }

  /**
   * Aggregated directory for Across integration:
   * - supported chains + tokens (from /chains)
   * - available routes (from /available-routes)
   * filtered by server-side allowlist and cached in Redis.
   */
  @GetMapping("/directory")
  public Mono<BridgeAcrossDirectoryResponse> directory() {
    return Mono.fromCallable(across::getDirectory).subscribeOn(Schedulers.boundedElastic());
  }

  @PostMapping("/swap/approval")
  public Mono<ResponseEntity<String>> swapApproval(@RequestBody(required = false) JsonNode body) {
    return swap.swapApproval(sanitizeSwapApprovalBody(body));
  }

  @GetMapping("/swap/chains")
  public Mono<ResponseEntity<String>> swapChains() {
    return swap.swapChains();
  }

  @GetMapping("/swap/tokens")
  public Mono<ResponseEntity<String>> swapTokens() {
    return swap.swapTokens();
  }

  @GetMapping("/swap/sources")
  public Mono<ResponseEntity<String>> swapSources(
      @RequestParam(value = "chainId", required = false) Integer chainId) {
    return swap.swapSources(chainId);
  }

  @GetMapping("/deposit/status")
  public Mono<ResponseEntity<String>> depositStatus(@RequestParam("depositTxnRef") String depositTxnRef) {
    String normalized = depositTxnRef == null ? "" : depositTxnRef.trim();
    if (normalized.isBlank()) {
      throw badRequest("Missing query param: depositTxnRef");
    }
    return swap.depositStatus(normalized);
  }
}

