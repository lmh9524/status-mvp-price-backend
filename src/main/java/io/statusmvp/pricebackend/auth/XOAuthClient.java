package io.statusmvp.pricebackend.auth;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class XOAuthClient {
  private static final Logger log = LoggerFactory.getLogger(XOAuthClient.class);
  private static final int MAX_LOG_BODY_CHARS = 800;
  private final WebClient webClient;
  private final AuthProperties authProperties;
  private final AuthMetrics metrics;

  public XOAuthClient(WebClient webClient, AuthProperties authProperties, AuthMetrics metrics) {
    this.webClient = webClient;
    this.authProperties = authProperties;
    this.metrics = metrics;
  }

  public String buildAuthorizeUrl(String state, String codeChallenge) {
    AuthProperties.X x = authProperties.getX();
    String scope = String.join(" ", x.scopeList());
    return UriComponentsBuilder.fromUriString(x.getAuthorizeEndpoint())
        .queryParam("response_type", "code")
        .queryParam("client_id", x.getClientId())
        .queryParam("redirect_uri", x.getRedirectUri())
        .queryParam("scope", scope)
        .queryParam("state", state)
        .queryParam("code_challenge", codeChallenge)
        .queryParam("code_challenge_method", "S256")
        .build()
        .encode(StandardCharsets.UTF_8)
        .toUriString();
  }

  public String exchangeCodeForAccessToken(String code, String codeVerifier) {
    AuthProperties.X x = authProperties.getX();
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "authorization_code");
    form.add("client_id", x.getClientId());
    form.add("redirect_uri", x.getRedirectUri());
    form.add("code_verifier", codeVerifier);
    form.add("code", code);

    String clientSecret = x.getClientSecret() == null ? "" : x.getClientSecret();
    String basic = "";
    if (!clientSecret.isBlank()) {
      basic =
          Base64.getEncoder()
              .encodeToString((x.getClientId() + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
    }

    JsonNode response;
    try {
      WebClient.RequestBodySpec req =
          webClient
              .post()
              .uri(x.getTokenEndpoint())
              .contentType(MediaType.APPLICATION_FORM_URLENCODED);
      if (!basic.isBlank()) {
        req = req.header(HttpHeaders.AUTHORIZATION, "Basic " + basic);
      }
      response =
          req.body(BodyInserters.fromFormData(form))
              .retrieve()
              .bodyToMono(JsonNode.class)
              .timeout(Duration.ofSeconds(10))
              .block();
    } catch (WebClientResponseException e) {
      metrics.providerUnavailable("x");
      log.warn(
          "x token exchange failed: status={} body={}",
          e.getRawStatusCode(),
          sanitizeProviderBody(e.getResponseBodyAsString(StandardCharsets.UTF_8)));
      throw new AuthException(
          AuthErrorCode.OAUTH_EXCHANGE_FAILED,
          "x oauth token exchange failed",
          502,
          null,
          Map.of("providerStatus", e.getRawStatusCode()));
    } catch (Exception e) {
      metrics.providerUnavailable("x");
      log.warn("x token exchange failed: {}", e.toString());
      throw new AuthException(
          AuthErrorCode.OAUTH_EXCHANGE_FAILED, "x oauth token exchange failed", 502, null, Map.of());
    }
    if (response == null) {
      metrics.providerUnavailable("x");
      throw new AuthException(
          AuthErrorCode.OAUTH_EXCHANGE_FAILED, "x oauth empty token response", 502, null, Map.of());
    }
    String accessToken = response.path("access_token").asText("");
    if (accessToken.isBlank()) {
      metrics.providerUnavailable("x");
      throw new AuthException(
          AuthErrorCode.OAUTH_EXCHANGE_FAILED, "x oauth missing access token", 502, null, Map.of());
    }
    return accessToken;
  }

  public String fetchUserId(String accessToken) {
    AuthProperties.X x = authProperties.getX();
    JsonNode response;
    try {
      response =
          webClient
              .get()
              .uri(x.getUserinfoEndpoint())
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
              .retrieve()
              .bodyToMono(JsonNode.class)
              .timeout(Duration.ofSeconds(10))
              .block();
    } catch (WebClientResponseException e) {
      metrics.providerUnavailable("x");
      int status = e.getRawStatusCode();
      log.warn(
          "x userinfo failed: status={} body={}",
          status,
          sanitizeProviderBody(e.getResponseBodyAsString(StandardCharsets.UTF_8)));

      if (status == 401) {
        throw new AuthException(
            AuthErrorCode.UNAUTHORIZED,
            "x provider unauthorized",
            502,
            null,
            Map.of("providerStatus", status));
      }
      if (status == 403) {
        throw new AuthException(
            AuthErrorCode.FORBIDDEN, "x provider forbidden", 502, null, Map.of("providerStatus", status));
      }
      if (status == 429) {
        int retryAfter = parseRetryAfterSeconds(e);
        throw new AuthException(
            AuthErrorCode.RATE_LIMITED,
            "x rate limited",
            429,
            retryAfter > 0 ? retryAfter : null,
            Map.of("providerStatus", status, "scope", "x"));
      }
      throw new AuthException(
          AuthErrorCode.PROVIDER_UNAVAILABLE, "x provider unavailable", 503, null, Map.of("providerStatus", status));
    } catch (Exception e) {
      metrics.providerUnavailable("x");
      log.warn("x userinfo failed: {}", e.toString());
      throw new AuthException(
          AuthErrorCode.PROVIDER_UNAVAILABLE, "x provider unavailable", 503, null, Map.of());
    }
    String userId = response == null ? "" : response.path("data").path("id").asText("");
    if (userId.isBlank()) {
      metrics.providerUnavailable("x");
      throw new AuthException(
          AuthErrorCode.PROVIDER_UNAVAILABLE, "x user id not found", 503, null, Map.of());
    }
    return userId;
  }

  private static int parseRetryAfterSeconds(WebClientResponseException e) {
    if (e == null || e.getHeaders() == null) return 0;
    String retryAfter = e.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
    if (retryAfter != null) {
      String trimmed = retryAfter.trim();
      if (trimmed.matches("\\d+")) {
        try {
          return Integer.parseInt(trimmed);
        } catch (NumberFormatException ignored) {
          // fallthrough
        }
      }
    }

    // X frequently uses x-rate-limit-reset (epoch seconds).
    String reset = e.getHeaders().getFirst("x-rate-limit-reset");
    if (reset != null) {
      String trimmed = reset.trim();
      if (trimmed.matches("\\d+")) {
        try {
          long resetEpochSeconds = Long.parseLong(trimmed);
          long nowSeconds = System.currentTimeMillis() / 1000;
          long diff = resetEpochSeconds - nowSeconds;
          if (diff > 0 && diff <= Integer.MAX_VALUE) return (int) diff;
        } catch (NumberFormatException ignored) {
          // fallthrough
        }
      }
    }
    return 0;
  }

  private static String sanitizeProviderBody(String body) {
    if (body == null || body.isBlank()) return "";
    String sanitized = body;
    sanitized = sanitized.replaceAll("(?i)\\\"access_token\\\"\\s*:\\s*\\\"[^\\\"]+\\\"", "\"access_token\":\"***\"");
    sanitized =
        sanitized.replaceAll("(?i)\\\"refresh_token\\\"\\s*:\\s*\\\"[^\\\"]+\\\"", "\"refresh_token\":\"***\"");
    sanitized = sanitized.replaceAll("(?i)\\\"id_token\\\"\\s*:\\s*\\\"[^\\\"]+\\\"", "\"id_token\":\"***\"");
    sanitized =
        sanitized.replaceAll("(?i)\\\"client_secret\\\"\\s*:\\s*\\\"[^\\\"]+\\\"", "\"client_secret\":\"***\"");
    if (sanitized.length() <= MAX_LOG_BODY_CHARS) return sanitized;
    return sanitized.substring(0, MAX_LOG_BODY_CHARS) + "...(truncated)";
  }
}
