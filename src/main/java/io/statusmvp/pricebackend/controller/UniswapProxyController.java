package io.statusmvp.pricebackend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.statusmvp.pricebackend.auth.AuthUtils;
import io.statusmvp.pricebackend.service.UniswapProxyService;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping(path = "/api/v1/evm/uniswap", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
public class UniswapProxyController {
  private static final Logger log = LoggerFactory.getLogger(UniswapProxyController.class);
  private static final int XLAYER_CHAIN_ID = 196;
  private static final Set<String> ALLOWED_URGENCY = Set.of("normal", "fast", "urgent");

  private final UniswapProxyService uniswap;

  public UniswapProxyController(UniswapProxyService uniswap) {
    this.uniswap = uniswap;
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

  private static String summarizeValue(String value) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.length() <= 12) return normalized;
    return normalized.substring(0, 6) + "..." + normalized.substring(normalized.length() - 4);
  }

  private static ResponseStatusException badRequest(String message) {
    log.warn("uniswap proxy request rejected: reason={}", message);
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

  private static int requireChainId(JsonNode body, String fieldName) {
    JsonNode node = body.get(fieldName);
    if (node == null || !node.canConvertToInt()) {
      throw badRequest("Missing field: " + fieldName);
    }
    return node.intValue();
  }

  private static void requireSupportedChainId(int chainId, String fieldName) {
    if (chainId != XLAYER_CHAIN_ID) {
      throw badRequest("Unsupported chainId for " + fieldName + ": " + chainId);
    }
  }

  private static void rejectUniswapXHints(JsonNode body) {
    String routingPreference = body.path("routingPreference").asText("");
    if (routingPreference.toUpperCase(Locale.ROOT).contains("UNISWAPX")) {
      throw badRequest("UniswapX routing is not supported");
    }
    JsonNode protocols = body.get("protocols");
    if (protocols != null && protocols.isArray()) {
      for (JsonNode protocol : protocols) {
        if (protocol != null
            && protocol.asText("").toUpperCase(Locale.ROOT).contains("UNISWAPX")) {
          throw badRequest("UniswapX protocols are not supported");
        }
      }
    }
  }

  private static String sanitizeUrgency(JsonNode body) {
    String urgency = body.path("urgency").asText("urgent").trim().toLowerCase(Locale.ROOT);
    if (!ALLOWED_URGENCY.contains(urgency)) {
      throw badRequest("Unsupported urgency: " + urgency);
    }
    return urgency;
  }

  private static void copyPositiveNumberOrDefault(
      JsonNode body, ObjectNode target, String fieldName, double defaultValue) {
    JsonNode node = body.get(fieldName);
    if (node == null || node.isNull()) {
      target.put(fieldName, defaultValue);
      return;
    }
    if (!node.isNumber()) {
      throw badRequest("Invalid numeric field: " + fieldName);
    }
    BigDecimal decimal = node.decimalValue();
    if (decimal.compareTo(BigDecimal.ZERO) <= 0) {
      throw badRequest("Invalid numeric field: " + fieldName);
    }
    target.set(fieldName, node.deepCopy());
  }

  private static ObjectNode sanitizeCheckApprovalBody(JsonNode body) {
    JsonNode request = requireObjectBody(body);
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    out.put("walletAddress", requireText(request, "walletAddress"));
    out.put("token", requireText(request, "token"));
    out.put("amount", requirePositiveIntegerString(request, "amount"));
    int chainId = requireChainId(request, "chainId");
    requireSupportedChainId(chainId, "chainId");
    out.put("chainId", chainId);
    out.put("includeGasInfo", true);
    out.put("urgency", sanitizeUrgency(request));
    return out;
  }

  private static ObjectNode sanitizeQuoteBody(JsonNode body) {
    JsonNode request = requireObjectBody(body);
    rejectUniswapXHints(request);
    int tokenInChainId = requireChainId(request, "tokenInChainId");
    int tokenOutChainId = requireChainId(request, "tokenOutChainId");
    requireSupportedChainId(tokenInChainId, "tokenInChainId");
    requireSupportedChainId(tokenOutChainId, "tokenOutChainId");
    if (tokenInChainId != tokenOutChainId) {
      throw badRequest("Cross-chain swaps are not supported");
    }

    ObjectNode out = JsonNodeFactory.instance.objectNode();
    out.put("type", "EXACT_INPUT");
    out.put("amount", requirePositiveIntegerString(request, "amount"));
    out.put("tokenInChainId", tokenInChainId);
    out.put("tokenOutChainId", tokenOutChainId);
    out.put("tokenIn", requireText(request, "tokenIn"));
    out.put("tokenOut", requireText(request, "tokenOut"));
    out.put("swapper", requireText(request, "swapper"));
    out.put("generatePermitAsTransaction", false);
    copyPositiveNumberOrDefault(request, out, "slippageTolerance", 1.0d);
    out.put("routingPreference", "BEST_PRICE");
    ArrayNode protocols = out.putArray("protocols");
    protocols.add("V2");
    protocols.add("V3");
    protocols.add("V4");
    out.put("urgency", sanitizeUrgency(request));
    out.put("permitAmount", "FULL");
    return out;
  }

  private static ObjectNode sanitizeSwapBody(JsonNode body) {
    JsonNode request = requireObjectBody(body);
    String routing = requireText(request, "routing").trim().toUpperCase(Locale.ROOT);
    if (!"CLASSIC".equals(routing)) {
      throw badRequest("Unsupported routing: " + routing);
    }
    JsonNode quote = request.get("quote");
    if (quote == null || !quote.isObject()) {
      throw badRequest("Missing field: quote");
    }
    int chainId = requireChainId(quote, "chainId");
    requireSupportedChainId(chainId, "quote.chainId");

    ObjectNode out = JsonNodeFactory.instance.objectNode();
    out.set("quote", quote.deepCopy());
    JsonNode signature = request.get("signature");
    JsonNode permitData = request.get("permitData");
    boolean hasSignature = signature != null && !signature.asText("").trim().isBlank();
    boolean hasPermitData = permitData != null && !permitData.isNull();
    if (hasSignature != hasPermitData) {
      throw badRequest("signature and permitData must be provided together");
    }
    if (hasSignature) {
      out.put("signature", signature.asText().trim());
      out.set("permitData", permitData.deepCopy());
    }
    out.put(
        "refreshGasPrice",
        request.has("refreshGasPrice") && request.get("refreshGasPrice").isBoolean()
            ? request.get("refreshGasPrice").booleanValue()
            : true);
    out.put(
        "simulateTransaction",
        request.has("simulateTransaction") && request.get("simulateTransaction").isBoolean()
            ? request.get("simulateTransaction").booleanValue()
            : false);
    out.put("safetyMode", "SAFE");
    out.put("urgency", sanitizeUrgency(request));
    JsonNode deadline = request.get("deadline");
    if (deadline != null && !deadline.isNull()) {
      if (!deadline.canConvertToLong()) {
        throw badRequest("Invalid numeric field: deadline");
      }
      out.put("deadline", deadline.longValue());
    }
    return out;
  }

  @PostMapping("/check_approval")
  public Mono<ResponseEntity<String>> checkApproval(
      @RequestBody(required = false) JsonNode body, ServerWebExchange exchange) {
    String clientIp = resolveClientIp(exchange);
    ObjectNode request = sanitizeCheckApprovalBody(body);
    log.info(
        "uniswap check_approval accepted: clientIp={}, walletAddress={}, token={}, chainId={}, amount={}, urgency={}",
        clientIp,
        summarizeValue(request.path("walletAddress").asText("")),
        summarizeValue(request.path("token").asText("")),
        request.path("chainId").asInt(),
        request.path("amount").asText(""),
        request.path("urgency").asText(""));
    return uniswap.checkApproval(request, clientIp);
  }

  @PostMapping("/quote")
  public Mono<ResponseEntity<String>> quote(
      @RequestBody(required = false) JsonNode body, ServerWebExchange exchange) {
    String clientIp = resolveClientIp(exchange);
    ObjectNode request = sanitizeQuoteBody(body);
    log.info(
        "uniswap quote accepted: clientIp={}, tokenInChainId={}, tokenOutChainId={}, tokenIn={}, tokenOut={}, swapper={}, amount={}, urgency={}",
        clientIp,
        request.path("tokenInChainId").asInt(),
        request.path("tokenOutChainId").asInt(),
        summarizeValue(request.path("tokenIn").asText("")),
        summarizeValue(request.path("tokenOut").asText("")),
        summarizeValue(request.path("swapper").asText("")),
        request.path("amount").asText(""),
        request.path("urgency").asText(""));
    return uniswap.quote(request, clientIp);
  }

  @PostMapping("/swap")
  public Mono<ResponseEntity<String>> swap(
      @RequestBody(required = false) JsonNode body, ServerWebExchange exchange) {
    String clientIp = resolveClientIp(exchange);
    ObjectNode request = sanitizeSwapBody(body);
    JsonNode quote = request.path("quote");
    log.info(
        "uniswap swap accepted: clientIp={}, chainId={}, hasPermitSignature={}, refreshGasPrice={}, simulateTransaction={}, urgency={}",
        clientIp,
        quote.path("chainId").asInt(),
        request.has("signature"),
        request.path("refreshGasPrice").asBoolean(),
        request.path("simulateTransaction").asBoolean(),
        request.path("urgency").asText(""));
    return uniswap.swap(request, clientIp);
  }
}
