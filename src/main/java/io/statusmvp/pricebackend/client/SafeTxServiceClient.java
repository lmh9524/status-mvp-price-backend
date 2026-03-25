package io.statusmvp.pricebackend.client;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

@Component
public class SafeTxServiceClient {
  private final WebClient webClient;
  private final String baseUrl;
  private final String apiKey;
  private final Duration timeout;

  public SafeTxServiceClient(
      WebClient webClient,
      @Value("${SAFE_TX_SERVICE_BASE_URL:https://api.safe.global/tx-service}") String baseUrl,
      @Value("${SAFE_TX_SERVICE_API_KEY:}") String apiKey,
      @Value("${SAFE_TX_SERVICE_TIMEOUT_MS:12000}") long timeoutMs) {
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

  private void applyAuth(HttpHeaders headers) {
    if (!apiKey.isBlank()) {
      headers.setBearerAuth(apiKey);
    }
  }

  private URI buildUri(String chain, String path, MultiValueMap<String, String> query) {
    String p = path == null ? "" : path.trim();
    if (!p.startsWith("/")) p = "/" + p;
    UriComponentsBuilder b = UriComponentsBuilder.fromUriString(baseUrl + "/" + chain + p);
    if (query != null && !query.isEmpty()) {
      b.queryParams(query);
    }
    return b.build(true).toUri();
  }

  private static MediaType responseContentType(ClientResponse resp) {
    return resp.headers().contentType().orElse(MediaType.APPLICATION_JSON);
  }

  private static Mono<ResponseEntity<String>> toResponseEntity(ClientResponse resp) {
    return resp
        .bodyToMono(String.class)
        .defaultIfEmpty("")
        .map(
            body -> {
              ResponseEntity.BodyBuilder b =
                  ResponseEntity.status(resp.statusCode()).contentType(responseContentType(resp));
              String retryAfter = resp.headers().asHttpHeaders().getFirst(HttpHeaders.RETRY_AFTER);
              if (retryAfter != null && !retryAfter.isBlank()) {
                b.header(HttpHeaders.RETRY_AFTER, retryAfter.trim());
              }
              return b.body(body);
            });
  }

  public Mono<ResponseEntity<String>> get(String chain, String path, MultiValueMap<String, String> query) {
    if (baseUrl.isBlank()) {
      return Mono.just(ResponseEntity.status(503).body("{\"error\":\"SAFE_TX_SERVICE_BASE_URL not set\"}"));
    }

    URI uri = buildUri(chain, path, query);
    return webClient
        .get()
        .uri(uri)
        .headers(this::applyAuth)
        .exchangeToMono(SafeTxServiceClient::toResponseEntity)
        .timeout(timeout);
  }

  public Mono<ResponseEntity<String>> post(String chain, String path, MultiValueMap<String, String> query, JsonNode body) {
    if (baseUrl.isBlank()) {
      return Mono.just(ResponseEntity.status(503).body("{\"error\":\"SAFE_TX_SERVICE_BASE_URL not set\"}"));
    }

    URI uri = buildUri(chain, path, query);
    return webClient
        .post()
        .uri(uri)
        .headers(
            h -> {
              applyAuth(h);
              h.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
            })
        .bodyValue(body == null ? "{}" : body)
        .exchangeToMono(SafeTxServiceClient::toResponseEntity)
        .timeout(timeout);
  }
}
