package io.statusmvp.pricebackend.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.Duration;
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
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

@Service
public class AcrossSwapProxyService {
  private static final Logger log = LoggerFactory.getLogger(AcrossSwapProxyService.class);
  private static final MediaType DEFAULT_CONTENT_TYPE = MediaType.APPLICATION_JSON;

  private final WebClient webClient;
  private final String apiBaseUrl;
  private final String apiKey;
  private final String integratorId;
  private final Duration timeout;

  public AcrossSwapProxyService(
      WebClient webClient,
      @Value("${app.bridge.across.apiBaseUrl:https://app.across.to/api}") String apiBaseUrl,
      @Value("${app.bridge.across.apiKey:}") String apiKey,
      @Value("${app.bridge.across.integratorId:}") String integratorId,
      @Value("${app.bridge.across.timeoutMs:12000}") long timeoutMs) {
    this.webClient = webClient;
    this.apiBaseUrl = normalizeBaseUrl(apiBaseUrl);
    this.apiKey = apiKey == null ? "" : apiKey.trim();
    this.integratorId = integratorId == null ? "" : integratorId.trim();
    this.timeout = Duration.ofMillis(Math.max(1000L, timeoutMs));
    log.info(
        "across swap proxy initialized: baseUrl={}, timeoutMs={}, apiKeyConfigured={}, integratorIdConfigured={}",
        this.apiBaseUrl,
        this.timeout.toMillis(),
        !this.apiKey.isBlank(),
        !this.integratorId.isBlank());
  }

  private static String normalizeBaseUrl(String value) {
    if (value == null) return "";
    String trimmed = value.trim();
    while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
    return trimmed;
  }

  private static String text(JsonNode body, String fieldName) {
    return body == null ? "" : body.path(fieldName).asText("").trim();
  }

  private static boolean hasText(JsonNode body, String fieldName) {
    return !text(body, fieldName).isBlank();
  }

  private static MediaType responseContentType(ClientResponse response) {
    return response.headers().contentType().orElse(DEFAULT_CONTENT_TYPE);
  }

  private static String truncateForLog(String value) {
    if (value == null) return "";
    String trimmed = value.trim();
    if (trimmed.length() <= 400) return trimmed;
    return trimmed.substring(0, 400) + "...";
  }

  private static Mono<ResponseEntity<String>> toResponseEntity(
      String operation, ClientResponse response) {
    return response
        .bodyToMono(String.class)
        .defaultIfEmpty("")
        .map(
            body -> {
              if (!response.statusCode().is2xxSuccessful()) {
                log.warn(
                    "[across-swap-proxy] upstream {} returned {} body={}",
                    operation,
                    response.statusCode().value(),
                    truncateForLog(body));
              }
              return ResponseEntity.status(response.statusCode())
                  .contentType(responseContentType(response))
                  .body(body);
            });
  }

  private static ResponseEntity<String> jsonErrorResponse(int status, String error, String detail) {
    StringBuilder body = new StringBuilder();
    body.append("{\"error\":\"").append(error).append("\"");
    if (detail != null && !detail.isBlank()) {
      body.append(",\"detail\":\"")
          .append(detail.replace("\\", "\\\\").replace("\"", "\\\""))
          .append("\"");
    }
    body.append("}");
    return ResponseEntity.status(status).contentType(DEFAULT_CONTENT_TYPE).body(body.toString());
  }

  private Mono<ResponseEntity<String>> rejectIfUnconfigured(String operation) {
    if (!apiBaseUrl.isBlank() && !apiKey.isBlank() && !integratorId.isBlank()) {
      return null;
    }
    log.warn(
        "[across-swap-proxy] rejected {}: apiBaseUrlConfigured={}, apiKeyConfigured={}, integratorIdConfigured={}",
        operation,
        !apiBaseUrl.isBlank(),
        !apiKey.isBlank(),
        !integratorId.isBlank());
    return Mono.just(
        jsonErrorResponse(
            503,
            "ACROSS_SWAP_API_NOT_CONFIGURED",
            "Across API key and integrator id are required"));
  }

  private Mono<ResponseEntity<String>> get(URI uri, String operation) {
    Mono<ResponseEntity<String>> rejected = rejectIfUnconfigured(operation);
    if (rejected != null) return rejected;

    return webClient
        .get()
        .uri(uri)
        .headers(
            headers -> {
              headers.setBearerAuth(apiKey);
              headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
            })
        .exchangeToMono(resp -> toResponseEntity(operation, resp))
        .timeout(timeout)
        .onErrorResume(
            TimeoutException.class,
            error -> {
              log.warn(
                  "[across-swap-proxy] upstream {} timed out after {} ms",
                  operation,
                  timeout.toMillis());
              return Mono.just(jsonErrorResponse(504, "upstream timeout", operation));
            })
        .onErrorResume(
            Exception.class,
            error -> {
              log.warn("[across-swap-proxy] upstream {} failed", operation, error);
              return Mono.just(jsonErrorResponse(502, "upstream request failed", operation));
            });
  }

  private UriComponentsBuilder baseBuilder(String path) {
    String normalizedPath = path == null ? "" : path.trim();
    if (!normalizedPath.startsWith("/")) normalizedPath = "/" + normalizedPath;
    return UriComponentsBuilder.fromUriString(apiBaseUrl + normalizedPath);
  }

  private URI buildSwapApprovalUri(JsonNode request) {
    UriComponentsBuilder builder =
        baseBuilder("/swap/approval")
            .queryParam("tradeType", text(request, "tradeType").isBlank() ? "exactInput" : text(request, "tradeType"))
            .queryParam("amount", text(request, "amount"))
            .queryParam("inputToken", text(request, "inputToken"))
            .queryParam("outputToken", text(request, "outputToken"))
            .queryParam("originChainId", request.path("originChainId").asLong())
            .queryParam("destinationChainId", request.path("destinationChainId").asLong())
            .queryParam("depositor", text(request, "depositor"))
            .queryParam("integratorId", integratorId);

    if (hasText(request, "recipient")) builder.queryParam("recipient", text(request, "recipient"));
    if (hasText(request, "refundAddress")) {
      builder.queryParam("refundAddress", text(request, "refundAddress"));
    }
    if (hasText(request, "slippage")) builder.queryParam("slippage", text(request, "slippage"));
    if (request.has("refundOnOrigin") && request.get("refundOnOrigin").isBoolean()) {
      builder.queryParam("refundOnOrigin", request.get("refundOnOrigin").booleanValue());
    }
    if (request.has("skipOriginTxEstimation") && request.get("skipOriginTxEstimation").isBoolean()) {
      builder.queryParam(
          "skipOriginTxEstimation", request.get("skipOriginTxEstimation").booleanValue());
    }
    return builder.build(true).toUri();
  }

  public Mono<ResponseEntity<String>> swapApproval(JsonNode request) {
    URI uri = buildSwapApprovalUri(request);
    return get(uri, "swap/approval");
  }

  public Mono<ResponseEntity<String>> swapChains() {
    return get(baseBuilder("/swap/chains").build(true).toUri(), "swap/chains");
  }

  public Mono<ResponseEntity<String>> swapTokens() {
    return get(baseBuilder("/swap/tokens").build(true).toUri(), "swap/tokens");
  }

  public Mono<ResponseEntity<String>> swapSources(Integer chainId) {
    UriComponentsBuilder builder = baseBuilder("/swap/sources");
    if (chainId != null && chainId > 0) builder.queryParam("chainId", chainId);
    return get(builder.build(true).toUri(), "swap/sources");
  }

  public Mono<ResponseEntity<String>> depositStatus(String depositTxnRef) {
    URI uri =
        baseBuilder("/deposit/status")
            .queryParam("depositTxnRef", depositTxnRef == null ? "" : depositTxnRef.trim())
            .build(true)
            .toUri();
    return get(uri, "deposit/status");
  }
}
