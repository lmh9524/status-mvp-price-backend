package io.statusmvp.pricebackend.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class TelegramOidcClient {
  private static final Logger log = LoggerFactory.getLogger(TelegramOidcClient.class);
  private static final int MAX_LOG_BODY_CHARS = 800;
  private static final String USER_AGENT = "Mozilla/5.0 (compatible; VeilWalletAuthBackend/1.0)";
  private static final long JWKS_CACHE_TTL_MS = 300_000L;

  private final WebClient webClient;
  private final AuthProperties authProperties;
  private final AuthMetrics metrics;

  private volatile JWKSet cachedJwkSet;
  private volatile long cachedJwkSetExpiresAtMs;

  public record TokenExchangeResult(String accessToken, String idToken) {}
  public record ValidatedIdentity(String providerUserId, String subject) {}

  public TelegramOidcClient(WebClient webClient, AuthProperties authProperties, AuthMetrics metrics) {
    this.webClient = webClient;
    this.authProperties = authProperties;
    this.metrics = metrics;
  }

  public String buildAuthorizeUrl(String state, String codeChallenge) {
    AuthProperties.Tg tg = authProperties.getTg();
    String clientId = resolveClientId();
    String scope = String.join(" ", tg.scopeList());
    return UriComponentsBuilder.fromUriString(tg.getAuthorizeEndpoint())
        .queryParam("response_type", "code")
        .queryParam("client_id", clientId)
        .queryParam("redirect_uri", tg.getRedirectUri())
        .queryParam("scope", scope)
        .queryParam("state", state)
        .queryParam("code_challenge", codeChallenge)
        .queryParam("code_challenge_method", "S256")
        .build()
        .encode(StandardCharsets.UTF_8)
        .toUriString();
  }

  public String exchangeCodeForProviderUserId(String code, String codeVerifier) {
    TokenExchangeResult tokens = exchangeCodeForTokens(code, codeVerifier);
    return validateIdToken(tokens.idToken(), null).providerUserId();
  }

  public TokenExchangeResult exchangeCodeForTokens(String code, String codeVerifier) {
    AuthProperties.Tg tg = authProperties.getTg();
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "authorization_code");
    form.add("redirect_uri", tg.getRedirectUri());
    form.add("code_verifier", codeVerifier);
    form.add("code", code);

    String basic =
        Base64.getEncoder()
            .encodeToString((resolveClientId() + ":" + tg.getClientSecret()).getBytes(StandardCharsets.UTF_8));

    JsonNode response;
    try {
      response =
          webClient
              .post()
              .uri(tg.getTokenEndpoint())
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .header(HttpHeaders.AUTHORIZATION, "Basic " + basic)
              .header(HttpHeaders.USER_AGENT, USER_AGENT)
              .body(BodyInserters.fromFormData(form))
              .retrieve()
              .bodyToMono(JsonNode.class)
              .timeout(Duration.ofSeconds(10))
              .block();
    } catch (WebClientResponseException e) {
      metrics.providerUnavailable("tg");
      log.warn(
          "tg token exchange failed: status={} headers={} body={}",
          e.getRawStatusCode(),
          providerDebugHeaders(e),
          sanitizeProviderBody(e.getResponseBodyAsString(StandardCharsets.UTF_8)));
      throw new AuthException(
          AuthErrorCode.OAUTH_EXCHANGE_FAILED,
          "telegram oauth token exchange failed",
          502,
          null,
          Map.of("providerStatus", e.getRawStatusCode()));
    } catch (Exception e) {
      metrics.providerUnavailable("tg");
      log.warn("tg token exchange failed: {}", e.toString());
      throw new AuthException(
          AuthErrorCode.OAUTH_EXCHANGE_FAILED, "telegram oauth token exchange failed", 502, null, Map.of());
    }

    if (response == null) {
      metrics.providerUnavailable("tg");
      throw new AuthException(
          AuthErrorCode.OAUTH_EXCHANGE_FAILED, "telegram oauth empty token response", 502, null, Map.of());
    }

    String accessToken = response.path("access_token").asText("");
    if (accessToken != null && accessToken.isBlank()) accessToken = null;
    String idToken = response.path("id_token").asText("");
    if (idToken == null || idToken.isBlank()) {
      metrics.providerUnavailable("tg");
      throw new AuthException(
          AuthErrorCode.OAUTH_EXCHANGE_FAILED, "telegram oauth missing id_token", 502, null, Map.of());
    }
    return new TokenExchangeResult(accessToken, idToken);
  }

  public String resolveClientId() {
    String clientId = trim(authProperties.getTg().resolvedClientId());
    if (clientId == null) {
      throw new AuthException(
          AuthErrorCode.PROVIDER_UNAVAILABLE, "telegram client id not configured", 503);
    }
    return clientId;
  }

  public ValidatedIdentity validateIdToken(String idToken, String expectedNonce) {
    AuthProperties.Tg tg = authProperties.getTg();
    String clientId = resolveClientId();
    SignedJWT jwt;
    try {
      jwt = SignedJWT.parse(idToken);
    } catch (ParseException e) {
      throw new AuthException(AuthErrorCode.UNAUTHORIZED, "telegram id token invalid", 401);
    }

    if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())) {
      throw new AuthException(AuthErrorCode.UNAUTHORIZED, "telegram id token invalid", 401);
    }

    RSAKey rsaKey = findVerificationKey(jwt);
    RSAPublicKey publicKey;
    try {
      publicKey = rsaKey.toRSAPublicKey();
    } catch (Exception e) {
      throw new AuthException(AuthErrorCode.UNAUTHORIZED, "telegram id token invalid", 401);
    }

    try {
      JWSVerifier verifier = new RSASSAVerifier(publicKey);
      if (!jwt.verify(verifier)) {
        throw new AuthException(AuthErrorCode.UNAUTHORIZED, "telegram id token invalid", 401);
      }
    } catch (AuthException e) {
      throw e;
    } catch (Exception e) {
      throw new AuthException(AuthErrorCode.UNAUTHORIZED, "telegram id token invalid", 401);
    }

    JWTClaimsSet claims;
    try {
      claims = jwt.getJWTClaimsSet();
    } catch (ParseException e) {
      throw new AuthException(AuthErrorCode.UNAUTHORIZED, "telegram id token invalid", 401);
    }

    String issuer = trim(claims.getIssuer());
    if (!tg.getIssuer().equals(issuer)) {
      throw new AuthException(AuthErrorCode.UNAUTHORIZED, "telegram id token invalid", 401);
    }

    List<String> audience = claims.getAudience();
    if (audience == null || audience.stream().noneMatch(clientId::equals)) {
      throw new AuthException(AuthErrorCode.UNAUTHORIZED, "telegram id token invalid", 401);
    }

    Date exp = claims.getExpirationTime();
    if (exp == null || exp.getTime() <= System.currentTimeMillis()) {
      throw new AuthException(AuthErrorCode.UNAUTHORIZED, "telegram id token expired", 401);
    }

    Date iat = claims.getIssueTime();
    if (iat != null && iat.getTime() > System.currentTimeMillis() + 60_000L) {
      throw new AuthException(AuthErrorCode.UNAUTHORIZED, "telegram id token invalid", 401);
    }

    String sub = trim(claims.getSubject());
    if (sub == null || sub.isBlank()) {
      throw new AuthException(AuthErrorCode.UNAUTHORIZED, "telegram id token invalid", 401);
    }

    if (expectedNonce != null) {
      String nonce = claimAsString(claims.getClaim("nonce"));
      if (nonce == null || !expectedNonce.equals(nonce)) {
        throw new AuthException(AuthErrorCode.UNAUTHORIZED, "telegram id token invalid", 401);
      }
    }

    String telegramId = claimAsString(claims.getClaim("id"));
    String providerUserId = telegramId == null ? sub : telegramId;
    return new ValidatedIdentity(providerUserId, sub);
  }

  private RSAKey findVerificationKey(SignedJWT jwt) {
    String kid = trim(jwt.getHeader().getKeyID());
    JWKSet set = loadJwkSet(false);
    RSAKey match = selectRsaKey(set, kid);
    if (match != null) return match;

    set = loadJwkSet(true);
    match = selectRsaKey(set, kid);
    if (match != null) return match;

    throw new AuthException(AuthErrorCode.PROVIDER_UNAVAILABLE, "telegram jwks unavailable", 503, 3, Map.of());
  }

  private synchronized JWKSet loadJwkSet(boolean forceRefresh) {
    long now = System.currentTimeMillis();
    if (!forceRefresh && cachedJwkSet != null && cachedJwkSetExpiresAtMs > now) {
      return cachedJwkSet;
    }

    AuthProperties.Tg tg = authProperties.getTg();
    try {
      String raw =
          webClient
              .get()
              .uri(tg.getJwksUri())
              .header(HttpHeaders.USER_AGENT, USER_AGENT)
              .retrieve()
              .bodyToMono(String.class)
              .timeout(Duration.ofSeconds(10))
              .block();
      if (raw == null || raw.isBlank()) {
        metrics.providerUnavailable("tg");
        throw new AuthException(AuthErrorCode.PROVIDER_UNAVAILABLE, "telegram jwks unavailable", 503, 3, Map.of());
      }
      JWKSet jwkSet = JWKSet.parse(raw);
      cachedJwkSet = jwkSet;
      cachedJwkSetExpiresAtMs = now + JWKS_CACHE_TTL_MS;
      return jwkSet;
    } catch (AuthException e) {
      throw e;
    } catch (Exception e) {
      metrics.providerUnavailable("tg");
      log.warn("tg jwks fetch failed: {}", e.toString());
      throw new AuthException(AuthErrorCode.PROVIDER_UNAVAILABLE, "telegram jwks unavailable", 503, 3, Map.of());
    }
  }

  private static RSAKey selectRsaKey(JWKSet set, String kid) {
    if (set == null) return null;
    for (JWK jwk : set.getKeys()) {
      if (!(jwk instanceof RSAKey rsa)) continue;
      if (kid == null || kid.isBlank()) return rsa;
      if (kid.equals(trim(rsa.getKeyID()))) return rsa;
    }
    return null;
  }

  private static String trim(String value) {
    if (value == null) return null;
    String out = value.trim();
    return out.isEmpty() ? null : out;
  }

  private static String claimAsString(Object value) {
    if (value == null) return null;
    if (value instanceof Number n) {
      return Long.toString(n.longValue());
    }
    if (value instanceof String s) {
      return trim(s);
    }
    return trim(String.valueOf(value));
  }

  private static Map<String, String> providerDebugHeaders(WebClientResponseException e) {
    if (e == null || e.getHeaders() == null) return Map.of();
    Map<String, String> out = new LinkedHashMap<>();
    addHeader(out, e, HttpHeaders.RETRY_AFTER);
    addHeader(out, e, HttpHeaders.DATE);
    addHeader(out, e, HttpHeaders.CONTENT_TYPE);
    addHeader(out, e, HttpHeaders.SERVER);
    return out;
  }

  private static void addHeader(Map<String, String> out, WebClientResponseException e, String name) {
    if (out == null || e == null || e.getHeaders() == null || name == null) return;
    String v = e.getHeaders().getFirst(name);
    if (v != null && !v.isBlank()) {
      out.put(name, v.trim());
    }
  }

  private static String sanitizeProviderBody(String body) {
    if (body == null || body.isBlank()) return "";
    String sanitized = body;
    sanitized = sanitized.replaceAll("(?i)\\\"access_token\\\"\\s*:\\s*\\\"[^\\\"]+\\\"", "\"access_token\":\"***\"");
    sanitized = sanitized.replaceAll("(?i)\\\"id_token\\\"\\s*:\\s*\\\"[^\\\"]+\\\"", "\"id_token\":\"***\"");
    sanitized =
        sanitized.replaceAll("(?i)\\\"client_secret\\\"\\s*:\\s*\\\"[^\\\"]+\\\"", "\"client_secret\":\"***\"");
    if (sanitized.length() <= MAX_LOG_BODY_CHARS) return sanitized;
    return sanitized.substring(0, MAX_LOG_BODY_CHARS) + "...(truncated)";
  }
}
