package io.statusmvp.pricebackend.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
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
  private static final String USER_AGENT = "Mozilla/5.0 (compatible; VeilWalletAuthBackend/1.0)";
  private static final ObjectMapper JWT_MAPPER = new ObjectMapper();
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
              .header(HttpHeaders.USER_AGENT, USER_AGENT)
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

    String userIdFromJwt = tryExtractNumericUserIdFromJwt(accessToken);
    if (userIdFromJwt != null) {
      return userIdFromJwt;
    }

    final int maxAttempts = 5;
    final String primaryEndpoint = x.getUserinfoEndpoint();
    final String secondaryEndpoint = alternateUserinfoEndpoint(primaryEndpoint);
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        String endpoint =
            secondaryEndpoint != null && attempt % 2 == 0 ? secondaryEndpoint : primaryEndpoint;
        JsonNode response =
            webClient
                .get()
                .uri(endpoint)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header(HttpHeaders.USER_AGENT, USER_AGENT)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(6))
                .block();
        String userId = response == null ? "" : response.path("data").path("id").asText("");
        if (userId.isBlank()) {
          metrics.providerUnavailable("x");
          throw new AuthException(
              AuthErrorCode.PROVIDER_UNAVAILABLE, "x user id not found", 503, null, Map.of());
        }
        return userId;
      } catch (WebClientResponseException e) {
        int status = e.getRawStatusCode();
        if (isTransientStatus(status) && attempt < maxAttempts) {
          log.warn(
              "x userinfo transient error: status={} attempt={}/{} endpoint={} headers={}",
              status,
              attempt,
              maxAttempts,
              secondaryEndpoint != null && attempt % 2 == 0 ? secondaryEndpoint : primaryEndpoint,
              providerDebugHeaders(e));
          sleepBackoff(attempt, parseRetryAfterSeconds(e));
          continue;
        }

        metrics.providerUnavailable("x");
        log.warn(
            "x userinfo failed: status={} endpoint={} headers={} body={}",
            status,
            secondaryEndpoint != null && attempt % 2 == 0 ? secondaryEndpoint : primaryEndpoint,
            providerDebugHeaders(e),
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
            AuthErrorCode.PROVIDER_UNAVAILABLE,
            "x provider unavailable",
            503,
            null,
            Map.of("providerStatus", status));
      } catch (AuthException e) {
        throw e;
      } catch (Exception e) {
        if (attempt < maxAttempts) {
          log.warn("x userinfo transient exception: attempt={}/{} err={}", attempt, maxAttempts, e.toString());
          sleepBackoff(attempt, 0);
          continue;
        }
        metrics.providerUnavailable("x");
        log.warn("x userinfo failed: {}", e.toString());
        throw new AuthException(
            AuthErrorCode.PROVIDER_UNAVAILABLE, "x provider unavailable", 503, null, Map.of());
      }
    }
    metrics.providerUnavailable("x");
    throw new AuthException(AuthErrorCode.PROVIDER_UNAVAILABLE, "x provider unavailable", 503, null, Map.of());
  }

  private static boolean isTransientStatus(int status) {
    return status == 502 || status == 503 || status == 504;
  }

  private static void sleepBackoff(int attempt, int retryAfterSeconds) {
    long ms = backoffMs(attempt, retryAfterSeconds);
    try {
      Thread.sleep(ms);
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
    }
  }

  private static long backoffMs(int attempt, int retryAfterSeconds) {
    int cappedAttempt = Math.max(1, Math.min(6, attempt));
    if (retryAfterSeconds > 0) {
      long cappedSeconds = Math.min(15L, retryAfterSeconds);
      return cappedSeconds * 1000L;
    }
    // Exponential backoff: 0.8s, 1.6s, 3.2s, 6.4s...
    long ms = 800L * (1L << (cappedAttempt - 1));
    return Math.min(10_000L, ms);
  }

  private static String alternateUserinfoEndpoint(String endpoint) {
    if (endpoint == null || endpoint.isBlank()) return null;
    if (endpoint.contains("api.twitter.com")) return endpoint.replace("api.twitter.com", "api.x.com");
    if (endpoint.contains("api.x.com")) return endpoint.replace("api.x.com", "api.twitter.com");
    return null;
  }

  private static Map<String, String> providerDebugHeaders(WebClientResponseException e) {
    if (e == null || e.getHeaders() == null) return Map.of();
    Map<String, String> out = new LinkedHashMap<>();
    addHeader(out, e, "x-transaction-id");
    addHeader(out, e, "x-response-time");
    addHeader(out, e, "x-rate-limit-limit");
    addHeader(out, e, "x-rate-limit-remaining");
    addHeader(out, e, "x-rate-limit-reset");
    addHeader(out, e, HttpHeaders.RETRY_AFTER);
    addHeader(out, e, "cf-ray");
    addHeader(out, e, HttpHeaders.SERVER);
    addHeader(out, e, "via");
    addHeader(out, e, HttpHeaders.DATE);
    addHeader(out, e, HttpHeaders.CONTENT_TYPE);
    return out;
  }

  private static void addHeader(Map<String, String> out, WebClientResponseException e, String name) {
    if (out == null || e == null || e.getHeaders() == null || name == null) return;
    String v = e.getHeaders().getFirst(name);
    if (v != null && !v.isBlank()) {
      out.put(name, v.trim());
    }
  }

  private static String tryExtractNumericUserIdFromJwt(String token) {
    if (token == null) return null;
    String t = token.trim();
    if (t.isEmpty()) return null;
    String[] parts = t.split("\\.");
    if (parts.length != 3) return null;

    byte[] payloadBytes = base64UrlDecode(parts[1]);
    if (payloadBytes.length == 0) return null;

    try {
      JsonNode payload = JWT_MAPPER.readTree(payloadBytes);
      if (payload == null) return null;
      String[] candidates = {"sub", "user_id", "uid"};
      for (String key : candidates) {
        String v = payload.path(key).asText("").trim();
        if (looksLikeNumericUserId(v)) return v;
      }
      return null;
    } catch (Exception ignored) {
      return null;
    }
  }

  private static boolean looksLikeNumericUserId(String value) {
    if (value == null) return false;
    String v = value.trim();
    if (v.isEmpty() || v.length() > 32) return false;
    for (int i = 0; i < v.length(); i++) {
      char c = v.charAt(i);
      if (c < '0' || c > '9') return false;
    }
    return true;
  }

  private static byte[] base64UrlDecode(String value) {
    if (value == null) return new byte[0];
    String v = value.trim();
    if (v.isEmpty()) return new byte[0];

    int mod = v.length() % 4;
    if (mod == 2) v += "==";
    else if (mod == 3) v += "=";
    else if (mod != 0) return new byte[0];

    try {
      return java.util.Base64.getUrlDecoder().decode(v);
    } catch (IllegalArgumentException ignored) {
      return new byte[0];
    }
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
