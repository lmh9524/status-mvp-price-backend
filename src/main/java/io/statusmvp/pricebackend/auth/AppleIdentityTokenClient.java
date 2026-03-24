package io.statusmvp.pricebackend.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

@Service
public class AppleIdentityTokenClient {
  private static final Logger log = LoggerFactory.getLogger(AppleIdentityTokenClient.class);
  private static final String USER_AGENT = "Mozilla/5.0 (compatible; VeilWalletAuthBackend/1.0)";
  private static final long JWKS_CACHE_TTL_MS = 300_000L;

  private final WebClient webClient;
  private final AuthProperties authProperties;
  private final AuthMetrics metrics;

  private volatile JWKSet cachedJwkSet;
  private volatile long cachedJwkSetExpiresAtMs;

  public record ValidatedIdentity(String providerUserId, String subject, String email) {}

  public AppleIdentityTokenClient(
      WebClient webClient, AuthProperties authProperties, AuthMetrics metrics) {
    this.webClient = webClient;
    this.authProperties = authProperties;
    this.metrics = metrics;
  }

  public ValidatedIdentity validateIdentityToken(String identityToken, String expectedNonce) {
    SignedJWT jwt;
    try {
      jwt = SignedJWT.parse(identityToken);
    } catch (ParseException e) {
      log.warn("apple identity token parse failed: {}", e.toString());
      throw new AuthException(AuthErrorCode.UNAUTHORIZED, "apple identity token invalid", 401);
    }

    if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())) {
      log.warn("apple identity token rejected: unsupported alg={}", jwt.getHeader().getAlgorithm());
      throw new AuthException(AuthErrorCode.UNAUTHORIZED, "apple identity token invalid", 401);
    }

    RSAKey rsaKey = findVerificationKey(jwt);
    try {
      JWSVerifier verifier = new RSASSAVerifier(rsaKey.toRSAPublicKey());
      if (!jwt.verify(verifier)) {
        log.warn("apple identity token signature verify failed");
        throw new AuthException(AuthErrorCode.UNAUTHORIZED, "apple identity token invalid", 401);
      }
    } catch (AuthException e) {
      throw e;
    } catch (Exception e) {
      log.warn("apple identity token signature error: {}", e.toString());
      throw new AuthException(AuthErrorCode.UNAUTHORIZED, "apple identity token invalid", 401);
    }

    JWTClaimsSet claims;
    try {
      claims = jwt.getJWTClaimsSet();
    } catch (ParseException e) {
      log.warn("apple identity token claims parse failed: {}", e.toString());
      throw new AuthException(AuthErrorCode.UNAUTHORIZED, "apple identity token invalid", 401);
    }

    AuthProperties.Apple apple = authProperties.getApple();
    String issuer = trim(claims.getIssuer());
    if (!apple.getIssuer().equals(issuer)) {
      log.warn("apple identity token rejected: issuer mismatch issuer={}", issuer);
      throw new AuthException(AuthErrorCode.UNAUTHORIZED, "apple identity token invalid", 401);
    }

    List<String> audience = claims.getAudience();
    if (audience == null || audience.stream().noneMatch(apple.audienceList()::contains)) {
      log.warn("apple identity token rejected: audience mismatch audience={}", audience);
      throw new AuthException(AuthErrorCode.UNAUTHORIZED, "apple identity token invalid", 401);
    }

    Date exp = claims.getExpirationTime();
    if (exp == null || exp.getTime() <= System.currentTimeMillis()) {
      log.warn("apple identity token rejected: token expired");
      throw new AuthException(AuthErrorCode.UNAUTHORIZED, "apple identity token expired", 401);
    }

    Date iat = claims.getIssueTime();
    if (iat != null && iat.getTime() > System.currentTimeMillis() + 60_000L) {
      log.warn("apple identity token rejected: issuedAt is in the future");
      throw new AuthException(AuthErrorCode.UNAUTHORIZED, "apple identity token invalid", 401);
    }

    String subject = trim(claims.getSubject());
    if (!StringUtils.hasText(subject)) {
      log.warn("apple identity token rejected: missing subject");
      throw new AuthException(AuthErrorCode.UNAUTHORIZED, "apple identity token invalid", 401);
    }

    if (StringUtils.hasText(expectedNonce)) {
      String nonce = claimAsString(claims.getClaim("nonce"));
      if (!expectedNonce.equals(nonce)) {
        log.warn("apple identity token rejected: nonce mismatch");
        throw new AuthException(AuthErrorCode.UNAUTHORIZED, "apple identity token invalid", 401);
      }
    }

    String email = claimAsString(claims.getClaim("email"));
    return new ValidatedIdentity(subject, subject, email);
  }

  private RSAKey findVerificationKey(SignedJWT jwt) {
    String kid = trim(jwt.getHeader().getKeyID());
    JWKSet set = loadJwkSet(false);
    RSAKey match = selectRsaKey(set, kid);
    if (match != null) return match;

    set = loadJwkSet(true);
    match = selectRsaKey(set, kid);
    if (match != null) return match;

    log.warn("apple identity token verification key not found: kid={}", kid);
    throw new AuthException(AuthErrorCode.PROVIDER_UNAVAILABLE, "apple jwks unavailable", 503, 3, Map.of());
  }

  private synchronized JWKSet loadJwkSet(boolean forceRefresh) {
    long now = System.currentTimeMillis();
    if (!forceRefresh && cachedJwkSet != null && cachedJwkSetExpiresAtMs > now) {
      return cachedJwkSet;
    }

    try {
      String raw =
          webClient
              .get()
              .uri(authProperties.getApple().getJwksUri())
              .header(HttpHeaders.USER_AGENT, USER_AGENT)
              .retrieve()
              .bodyToMono(String.class)
              .timeout(Duration.ofSeconds(10))
              .block();
      if (!StringUtils.hasText(raw)) {
        metrics.providerUnavailable("apple");
        log.warn("apple jwks fetch returned empty body");
        throw new AuthException(AuthErrorCode.PROVIDER_UNAVAILABLE, "apple jwks unavailable", 503, 3, Map.of());
      }
      JWKSet jwkSet = JWKSet.parse(raw);
      cachedJwkSet = jwkSet;
      cachedJwkSetExpiresAtMs = now + JWKS_CACHE_TTL_MS;
      return jwkSet;
    } catch (AuthException e) {
      throw e;
    } catch (Exception e) {
      metrics.providerUnavailable("apple");
      log.warn("apple jwks fetch failed: {}", e.toString());
      throw new AuthException(AuthErrorCode.PROVIDER_UNAVAILABLE, "apple jwks unavailable", 503, 3, Map.of());
    }
  }

  private static RSAKey selectRsaKey(JWKSet set, String kid) {
    if (set == null) return null;
    for (JWK jwk : set.getKeys()) {
      if (!(jwk instanceof RSAKey rsa)) continue;
      if (!StringUtils.hasText(kid)) return rsa;
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
    if (value instanceof String s) {
      return trim(s);
    }
    if (value instanceof Number n) {
      return Long.toString(n.longValue());
    }
    return trim(String.valueOf(value));
  }
}
