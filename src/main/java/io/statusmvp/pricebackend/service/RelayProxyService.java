package io.statusmvp.pricebackend.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

@Service
public class RelayProxyService {
  private static final Logger log = LoggerFactory.getLogger(RelayProxyService.class);
  private static final MediaType DEFAULT_CONTENT_TYPE = MediaType.APPLICATION_JSON;

  private final WebClient webClient;
  private final String baseUrl;
  private final String apiKey;
  private final Duration timeout;

  public RelayProxyService(
      WebClient webClient,
      @Value("${app.bridge.relay.apiBaseUrl:https://api.relay.link}") String baseUrl,
      @Value("${app.bridge.relay.apiKey:}") String apiKey,
      @Value("${app.bridge.relay.timeoutMs:12000}") long timeoutMs) {
    this.webClient = webClient;
    this.baseUrl = normalizeBaseUrl(baseUrl);
    this.apiKey = apiKey == null ? "" : apiKey.trim();
    this.timeout = Duration.ofMillis(Math.max(1000, timeoutMs));
  }

  private static String normalizeBaseUrl(String value) {
    if (value == null) return "";
    String trimmed = value.trim();
    while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
    return trimmed;
  }

  private static MediaType responseContentType(ClientResponse response) {
    return response.headers().contentType().orElse(DEFAULT_CONTENT_TYPE);
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
                    "[relay-proxy] upstream {} returned {} body={}",
                    operation,
                    response.statusCode().value(),
                    truncateForLog(body));
              }
              return ResponseEntity.status(response.statusCode())
                  .contentType(responseContentType(response))
                  .body(body);
            });
  }

  private static String truncateForLog(String value) {
    if (value == null) return "";
    String trimmed = value.trim();
    if (trimmed.length() <= 400) return trimmed;
    return trimmed.substring(0, 400) + "...";
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

  private URI buildUri(String path) {
    String normalizedPath = path == null ? "" : path.trim();
    if (!normalizedPath.startsWith("/")) normalizedPath = "/" + normalizedPath;
    return UriComponentsBuilder.fromUriString(baseUrl + normalizedPath).build(true).toUri();
  }

  private URI buildStatusUri(String requestId) {
    return UriComponentsBuilder.fromUriString(baseUrl + "/intents/status/v3")
        .queryParam("requestId", requestId)
        .build(true)
        .toUri();
  }

  private Mono<ResponseEntity<String>> exchange(
      HttpMethod method, URI uri, JsonNode body, String operation) {
    if (baseUrl.isBlank()) {
      return Mono.just(
          jsonErrorResponse(503, "RELAY_API_BASE_URL not set", "Relay proxy is not configured"));
    }

    WebClient.RequestBodySpec request =
        webClient
            .method(method)
            .uri(uri)
            .headers(
                headers -> {
                  headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
                  headers.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
                  if (!apiKey.isBlank()) {
                    headers.set("x-api-key", apiKey);
                  }
                });

    Mono<ResponseEntity<String>> responseMono =
        body == null
            ? request.exchangeToMono(resp -> toResponseEntity(operation, resp))
            : request.bodyValue(body).exchangeToMono(resp -> toResponseEntity(operation, resp));

    return responseMono
        .timeout(timeout)
        .onErrorResume(
            error -> {
              if (error instanceof TimeoutException) {
                log.warn("[relay-proxy] upstream {} timed out after {} ms", operation, timeout.toMillis());
                return Mono.just(jsonErrorResponse(504, "upstream timeout", operation));
              }
              log.warn("[relay-proxy] upstream {} failed", operation, error);
              return Mono.just(jsonErrorResponse(502, "upstream request failed", operation));
            });
  }

  public Mono<ResponseEntity<String>> quote(JsonNode body) {
    return exchange(HttpMethod.POST, buildUri("/quote/v2"), body, "quote");
  }

  public Mono<ResponseEntity<String>> quoteMultiInput(JsonNode body) {
    return exchange(
        HttpMethod.POST, buildUri("/execute/swap/multi-input"), body, "quote/multi-input");
  }

  public Mono<ResponseEntity<String>> getIntentStatus(String requestId) {
    return exchange(HttpMethod.GET, buildStatusUri(requestId), null, "intents/status");
  }
}
