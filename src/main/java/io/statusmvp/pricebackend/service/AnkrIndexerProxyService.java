package io.statusmvp.pricebackend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.time.Duration;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public class AnkrIndexerProxyService {
  private static final Logger log = LoggerFactory.getLogger(AnkrIndexerProxyService.class);
  private static final MediaType DEFAULT_CONTENT_TYPE = MediaType.APPLICATION_JSON;
  private static final int MAX_PAGE_SIZE = 100;
  private static final int MAX_PAGE_TOKEN_LENGTH = 512;
  private static final int MAX_BLOCKCHAIN_COUNT = 8;
  private static final String BALANCE_METHOD = "ankr_getAccountBalance";
  private static final int ANKR_INTERNAL_ERROR_CODE = -32603;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final Set<String> ALLOWED_METHODS =
      Set.of(
          "ankr_getAccountBalance",
          "ankr_getNFTsByOwner",
          "ankr_getTokenTransfers",
          "ankr_getTransactionsByAddress");

  private static final Set<String> ALLOWED_BLOCKCHAINS =
      Set.of(
          "eth",
          "optimism",
          "bsc",
          "base",
          "arbitrum",
          "polygon",
          "xlayer",
          "eth_sepolia",
          "bsc_testnet");

  private final WebClient webClient;
  private final String ankrBaseUrl;
  private final String ankrApiKey;
  private final Duration timeout;
  private final boolean ankrBalanceEmptyFallback;

  public AnkrIndexerProxyService(
      WebClient webClient,
      @Value("${app.portfolio.ankrBaseUrl:https://rpc.ankr.com/multichain}") String ankrBaseUrl,
      @Value("${app.portfolio.ankrApiKey:}") String ankrApiKey,
      @Value("${app.portfolio.timeoutMs:12000}") long timeoutMs,
      @Value("${app.portfolio.ankrBalanceEmptyFallback:true}") boolean ankrBalanceEmptyFallback) {
    this.webClient = webClient;
    this.ankrBaseUrl = normalizeBaseUrl(ankrBaseUrl);
    this.ankrApiKey = ankrApiKey == null ? "" : ankrApiKey.trim();
    this.timeout = Duration.ofMillis(Math.max(1000L, timeoutMs));
    this.ankrBalanceEmptyFallback = ankrBalanceEmptyFallback;
  }

  public Mono<ResponseEntity<String>> proxy(JsonNode body) {
    ValidationResult validation = validate(body);
    if (!validation.valid()) {
      log.warn("[ankr-indexer-proxy] 拒绝非法请求: {}", validation.message());
      return Mono.just(badRequest(validation.message()));
    }
    if (ankrBaseUrl.isBlank() || ankrApiKey.isBlank()) {
      log.error("[ankr-indexer-proxy] Ankr 上游未配置，已拒绝请求");
      return Mono.just(
          ResponseEntity.status(503)
              .contentType(DEFAULT_CONTENT_TYPE)
              .body("{\"error\":\"ANKR upstream is not configured\"}"));
    }

    String method = body.path("method").asText("");
    return webClient
        .post()
        .uri(URI.create(ankrBaseUrl + "/" + ankrApiKey))
        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .bodyValue(body)
        .exchangeToMono(response -> toResponseEntity(response, method, body))
        .timeout(timeout)
        .doOnNext(
            response -> {
              if (!response.getStatusCode().is2xxSuccessful()) {
                log.warn(
                    "[ankr-indexer-proxy] 上游返回非成功状态 method={} status={}",
                    method,
                    response.getStatusCode().value());
              }
            })
        .onErrorResume(
            err -> {
              if (err instanceof TimeoutException) {
                log.warn("[ankr-indexer-proxy] 上游超时 method={}", method);
                return Mono.just(
                    ResponseEntity.status(504)
                        .contentType(DEFAULT_CONTENT_TYPE)
                        .body("{\"error\":\"ANKR upstream timeout\"}"));
              }
              log.error("[ankr-indexer-proxy] 上游请求失败 method={}", method, err);
              return Mono.just(
                  ResponseEntity.status(502)
                      .contentType(DEFAULT_CONTENT_TYPE)
                      .body("{\"error\":\"ANKR upstream request failed\"}"));
            });
  }

  private ValidationResult validate(JsonNode body) {
    if (body == null || !body.isObject()) {
      return ValidationResult.invalid("request body must be a JSON object");
    }

    String method = body.path("method").asText("").trim();
    if (!ALLOWED_METHODS.contains(method)) {
      return ValidationResult.invalid("unsupported ANKR method");
    }

    JsonNode params = body.get("params");
    if (params == null || !params.isObject()) {
      return ValidationResult.invalid("params must be an object");
    }

    ValidationResult blockchainValidation = validateBlockchains(params.get("blockchain"));
    if (!blockchainValidation.valid()) {
      return blockchainValidation;
    }

    ValidationResult pageSizeValidation = validatePageSize(params.get("pageSize"));
    if (!pageSizeValidation.valid()) {
      return pageSizeValidation;
    }

    ValidationResult pageTokenValidation = validatePageToken(params.get("pageToken"));
    if (!pageTokenValidation.valid()) {
      return pageTokenValidation;
    }

    return ValidationResult.ok();
  }

  private ValidationResult validateBlockchains(JsonNode blockchainNode) {
    if (blockchainNode == null || blockchainNode.isNull()) {
      return ValidationResult.invalid("blockchain is required");
    }

    if (blockchainNode.isTextual()) {
      String blockchain = blockchainNode.asText("").trim().toLowerCase(Locale.ROOT);
      if (!ALLOWED_BLOCKCHAINS.contains(blockchain)) {
        return ValidationResult.invalid("unsupported blockchain");
      }
      return ValidationResult.ok();
    }

    if (!blockchainNode.isArray()) {
      return ValidationResult.invalid("blockchain must be a string or array");
    }

    if (blockchainNode.size() < 1 || blockchainNode.size() > MAX_BLOCKCHAIN_COUNT) {
      return ValidationResult.invalid("blockchain array size is invalid");
    }

    Iterator<JsonNode> iterator = blockchainNode.elements();
    while (iterator.hasNext()) {
      JsonNode node = iterator.next();
      if (!node.isTextual()) {
        return ValidationResult.invalid("blockchain array contains invalid value");
      }
      String blockchain = node.asText("").trim().toLowerCase(Locale.ROOT);
      if (!ALLOWED_BLOCKCHAINS.contains(blockchain)) {
        return ValidationResult.invalid("unsupported blockchain");
      }
    }
    return ValidationResult.ok();
  }

  private ValidationResult validatePageSize(JsonNode pageSizeNode) {
    if (pageSizeNode == null || pageSizeNode.isNull()) {
      return ValidationResult.ok();
    }
    if (!pageSizeNode.canConvertToInt()) {
      return ValidationResult.invalid("pageSize must be an integer");
    }
    int pageSize = pageSizeNode.asInt();
    if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
      return ValidationResult.invalid("pageSize is out of range");
    }
    return ValidationResult.ok();
  }

  private ValidationResult validatePageToken(JsonNode pageTokenNode) {
    if (pageTokenNode == null || pageTokenNode.isNull()) {
      return ValidationResult.ok();
    }
    if (!pageTokenNode.isTextual()) {
      return ValidationResult.invalid("pageToken must be a string");
    }
    String pageToken = pageTokenNode.asText("");
    if (pageToken.length() > MAX_PAGE_TOKEN_LENGTH) {
      return ValidationResult.invalid("pageToken is too long");
    }
    return ValidationResult.ok();
  }

  private static String normalizeBaseUrl(String value) {
    if (value == null) return "";
    String trimmed = value.trim();
    while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
    return trimmed;
  }

  private static ResponseEntity<String> badRequest(String message) {
    return ResponseEntity.badRequest()
        .contentType(DEFAULT_CONTENT_TYPE)
        .body("{\"error\":\"" + escapeJson(message) + "\"}");
  }

  private static String escapeJson(String value) {
    return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private Mono<ResponseEntity<String>> toResponseEntity(
      ClientResponse response, String method, JsonNode requestBody) {
    return response
        .bodyToMono(String.class)
        .defaultIfEmpty("")
        .map(
            body -> {
              String effectiveBody = body;
              if (ankrBalanceEmptyFallback && isBalanceInternalError(method, body)) {
                log.warn(
                    "[ankr-indexer-proxy] ankr_getAccountBalance 上游返回 internal error"
                        + "（Ankr 对部分地址/分片的已知行为），已兜底为空余额结果");
                effectiveBody = buildEmptyBalanceResult(requestBody);
              }
              return ResponseEntity.status(response.statusCode())
                  .contentType(response.headers().contentType().orElse(DEFAULT_CONTENT_TYPE))
                  .body(effectiveBody);
            });
  }

  /**
   * Ankr 的 ankr_getAccountBalance 对部分地址（常见为无链上活动的地址，部分分片）会返回 -32603
   * internal error 而非空列表。这里识别该场景，以便上层返回与 Ankr 正常空结果一致的响应。
   */
  private static boolean isBalanceInternalError(String method, String body) {
    if (!BALANCE_METHOD.equals(method) || body == null || body.isBlank()) {
      return false;
    }
    try {
      JsonNode root = MAPPER.readTree(body);
      JsonNode error = root.path("error");
      if (error.isMissingNode() || error.isNull()) {
        return false;
      }
      return error.path("code").asInt(Integer.MAX_VALUE) == ANKR_INTERNAL_ERROR_CODE;
    } catch (Exception e) {
      return false;
    }
  }

  private static String buildEmptyBalanceResult(JsonNode requestBody) {
    ObjectNode result = MAPPER.createObjectNode();
    result.put("totalBalanceUsd", "0");
    result.put("totalCount", 0);
    result.set("assets", MAPPER.createArrayNode());

    ObjectNode payload = MAPPER.createObjectNode();
    payload.put("jsonrpc", "2.0");
    JsonNode id = requestBody.path("id");
    if (id.isMissingNode() || id.isNull()) {
      payload.put("id", 1);
    } else {
      payload.set("id", id);
    }
    payload.set("result", result);
    return payload.toString();
  }

  private record ValidationResult(boolean valid, String message) {
    static ValidationResult ok() {
      return new ValidationResult(true, "");
    }

    static ValidationResult invalid(String message) {
      return new ValidationResult(false, message);
    }
  }
}
