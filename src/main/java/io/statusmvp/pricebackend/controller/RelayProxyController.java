package io.statusmvp.pricebackend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.statusmvp.pricebackend.service.RelayProxyService;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping(path = "/api/v1/bridge/relay", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
public class RelayProxyController {
  private static final Set<String> ALLOWED_QUOTE_TRADE_TYPES =
      Set.of("EXACT_INPUT", "EXACT_OUTPUT", "EXPECTED_OUTPUT");
  private static final Set<String> ALLOWED_MULTI_INPUT_TRADE_TYPES =
      Set.of("EXACT_INPUT", "EXACT_OUTPUT");

  private final RelayProxyService relay;

  public RelayProxyController(RelayProxyService relay) {
    this.relay = relay;
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

  private static String requirePositiveIntegerString(JsonNode body, String fieldName) {
    String value = requireText(body, fieldName);
    if (!value.matches("^\\d+$") || "0".equals(value)) {
      throw badRequest("Invalid numeric field: " + fieldName);
    }
    return value;
  }

  private static int requirePositiveChainId(JsonNode body, String fieldName) {
    JsonNode node = body.get(fieldName);
    if (node == null || !node.canConvertToInt()) {
      throw badRequest("Missing field: " + fieldName);
    }
    int value = node.intValue();
    if (value <= 0) {
      throw badRequest("Invalid numeric field: " + fieldName);
    }
    return value;
  }

  private static boolean requireBoolean(JsonNode body, String fieldName) {
    JsonNode node = body.get(fieldName);
    if (node == null || !node.isBoolean()) {
      throw badRequest("Invalid boolean field: " + fieldName);
    }
    return node.booleanValue();
  }

  private static void copyIfPresent(JsonNode body, ObjectNode out, String fieldName) {
    JsonNode node = body.get(fieldName);
    if (node != null && !node.isNull()) {
      out.set(fieldName, node.deepCopy());
    }
  }

  private static String requireTradeType(
      JsonNode body, String fieldName, Set<String> allowedTradeTypes) {
    String tradeType = requireText(body, fieldName).trim().toUpperCase(Locale.ROOT);
    if (!allowedTradeTypes.contains(tradeType)) {
      throw badRequest("Unsupported " + fieldName + ": " + tradeType);
    }
    return tradeType;
  }

  private static ObjectNode sanitizeQuoteBody(JsonNode body) {
    JsonNode request = requireObjectBody(body);
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    out.put("user", requireText(request, "user"));
    out.put("originChainId", requirePositiveChainId(request, "originChainId"));
    out.put(
        "destinationChainId", requirePositiveChainId(request, "destinationChainId"));
    out.put("originCurrency", requireText(request, "originCurrency"));
    out.put("destinationCurrency", requireText(request, "destinationCurrency"));
    out.put("amount", requirePositiveIntegerString(request, "amount"));
    out.put("tradeType", requireTradeType(request, "tradeType", ALLOWED_QUOTE_TRADE_TYPES));

    copyIfPresent(request, out, "recipient");
    copyIfPresent(request, out, "refundTo");
    copyIfPresent(request, out, "topupGasAmount");
    copyIfPresent(request, out, "useReceiver");
    copyIfPresent(request, out, "enableTrueExactOutput");
    copyIfPresent(request, out, "explicitDeposit");
    copyIfPresent(request, out, "useExternalLiquidity");
    copyIfPresent(request, out, "txs");
    copyIfPresent(request, out, "txsGasLimit");
    copyIfPresent(request, out, "referrer");
    copyIfPresent(request, out, "gasLimitForDepositSpecifiedTxs");
    copyIfPresent(request, out, "originGasOverhead");
    copyIfPresent(request, out, "slippageTolerance");
    return out;
  }

  private static ArrayNode sanitizeOrigins(JsonNode body) {
    JsonNode originsNode = body.get("origins");
    if (originsNode == null || !originsNode.isArray() || originsNode.isEmpty()) {
      throw badRequest("Missing field: origins");
    }
    ArrayNode out = JsonNodeFactory.instance.arrayNode();
    for (JsonNode origin : originsNode) {
      if (origin == null || !origin.isObject()) {
        throw badRequest("Invalid origins item");
      }
      ObjectNode sanitized = JsonNodeFactory.instance.objectNode();
      sanitized.put("chainId", requirePositiveChainId(origin, "chainId"));
      sanitized.put("currency", requireText(origin, "currency"));
      sanitized.put("amount", requirePositiveIntegerString(origin, "amount"));
      sanitized.put("user", requireText(origin, "user"));
      out.add(sanitized);
    }
    return out;
  }

  private static ObjectNode sanitizeMultiInputBody(JsonNode body) {
    JsonNode request = requireObjectBody(body);
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    out.put("user", requireText(request, "user"));
    out.set("origins", sanitizeOrigins(request));
    out.put(
        "destinationChainId", requirePositiveChainId(request, "destinationChainId"));
    out.put("destinationCurrency", requireText(request, "destinationCurrency"));
    out.put(
        "tradeType",
        requireTradeType(request, "tradeType", ALLOWED_MULTI_INPUT_TRADE_TYPES));
    out.put("amount", requirePositiveIntegerString(request, "amount"));
    out.put(
        "partial",
        request.has("partial") ? requireBoolean(request, "partial") : false);

    copyIfPresent(request, out, "recipient");
    copyIfPresent(request, out, "refundTo");
    copyIfPresent(request, out, "txs");
    copyIfPresent(request, out, "txsGasLimit");
    copyIfPresent(request, out, "referrer");
    copyIfPresent(request, out, "gasLimitForDepositSpecifiedTxs");
    copyIfPresent(request, out, "originGasOverhead");
    copyIfPresent(request, out, "slippageTolerance");
    return out;
  }

  @PostMapping("/quote")
  public Mono<ResponseEntity<String>> quote(@RequestBody(required = false) JsonNode body) {
    return relay.quote(sanitizeQuoteBody(body));
  }

  @PostMapping("/quote/multi-input")
  public Mono<ResponseEntity<String>> quoteMultiInput(
      @RequestBody(required = false) JsonNode body) {
    return relay.quoteMultiInput(sanitizeMultiInputBody(body));
  }

  @GetMapping("/intents/status")
  public Mono<ResponseEntity<String>> intentStatus(@RequestParam("requestId") String requestId) {
    String normalized = requestId == null ? "" : requestId.trim();
    if (normalized.isBlank()) {
      throw badRequest("Missing query param: requestId");
    }
    return relay.getIntentStatus(normalized);
  }
}
