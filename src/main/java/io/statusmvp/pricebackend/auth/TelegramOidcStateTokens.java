package io.statusmvp.pricebackend.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Stateless Telegram OIDC state tokens.
 *
 * <p>When enabled (via {@code app.auth.tg.stateSecret}), {@code /api/v1/auth/tg/start} issues a signed
 * state token that contains the app redirect URI and a random nonce. The PKCE {@code code_verifier}
 * is deterministically derived from (secret, nonce) so {@code /api/v1/auth/tg/callback} does not
 * require a shared Redis entry to validate the state.
 */
public final class TelegramOidcStateTokens {
  private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder B64URL_DEC = Base64.getUrlDecoder();
  private static final int SIG_BYTES_LEN = 32;
  private static final int VERSION = 1;
  private static final long MAX_FUTURE_IAT_SKEW_SECONDS = 60;
  private static final String SIG_INFO = "tg-state-v1";
  private static final String PKCE_INFO = "tg-pkce-v1";

  private TelegramOidcStateTokens() {}

  public record Issued(String state, String codeVerifier) {}

  public record Parsed(String appRedirectUri, String codeVerifier, long issuedAtMs, long expiresAtMs) {}

  private record Payload(int v, String n, long iat, long exp, String aru) {}

  public static boolean looksLikeToken(String state) {
    return state != null && state.indexOf('.') > 0;
  }

  public static Issued issue(
      ObjectMapper objectMapper, String stateSecret, String appRedirectUri, long ttlSeconds, long nowMs) {
    byte[] secret = normalizeSecretBytes(stateSecret);
    if (secret.length < 32) {
      throw new IllegalArgumentException("tg.stateSecret must be at least 32 bytes");
    }

    long iatSec = Math.max(0, nowMs / 1000);
    long expSec = iatSec + Math.max(1, ttlSeconds);
    String nonce = AuthUtils.randomBase64Url(16);
    Payload payload = new Payload(VERSION, nonce, iatSec, expSec, appRedirectUri);

    String payloadB64;
    try {
      payloadB64 = B64URL.encodeToString(objectMapper.writeValueAsBytes(payload));
    } catch (Exception e) {
      throw new IllegalStateException("state serialization error", e);
    }

    byte[] sigBytes = hmacSha256(secret, signInput(payloadB64));
    String sigB64 = B64URL.encodeToString(sigBytes);
    String state = payloadB64 + "." + sigB64;

    String codeVerifier = derivePkceCodeVerifier(secret, nonce);
    return new Issued(state, codeVerifier);
  }

  public static Parsed parseAndVerify(
      ObjectMapper objectMapper, String stateSecret, String state, long nowMs) {
    if (state == null || state.isBlank()) return null;
    String[] parts = state.split("\\.", 2);
    if (parts.length != 2) return null;

    byte[] secret = normalizeSecretBytes(stateSecret);
    if (secret.length < 32) return null;

    String payloadB64 = parts[0];
    String sigB64 = parts[1];

    byte[] sig;
    byte[] payloadBytes;
    try {
      sig = B64URL_DEC.decode(sigB64);
      payloadBytes = B64URL_DEC.decode(payloadB64);
    } catch (IllegalArgumentException e) {
      return null;
    }

    if (sig.length != SIG_BYTES_LEN) return null;

    byte[] expectedSig = hmacSha256(secret, signInput(payloadB64));
    if (!MessageDigest.isEqual(expectedSig, sig)) return null;

    Payload payload;
    try {
      payload = objectMapper.readValue(payloadBytes, Payload.class);
    } catch (Exception e) {
      return null;
    }

    if (payload == null || payload.v != VERSION) return null;
    if (payload.n == null || payload.n.isBlank()) return null;

    long nowSec = Math.max(0, nowMs / 1000);
    if (payload.iat > nowSec + MAX_FUTURE_IAT_SKEW_SECONDS) return null;
    if (payload.exp <= nowSec) return null;
    if (payload.exp < payload.iat) return null;

    String codeVerifier = derivePkceCodeVerifier(secret, payload.n);
    long issuedAtMs = payload.iat * 1000;
    long expiresAtMs = payload.exp * 1000;
    return new Parsed(payload.aru, codeVerifier, issuedAtMs, expiresAtMs);
  }

  private static byte[] signInput(String payloadB64) {
    return (SIG_INFO + "|" + payloadB64).getBytes(StandardCharsets.US_ASCII);
  }

  private static String derivePkceCodeVerifier(byte[] secret, String nonce) {
    byte[] digest = hmacSha256(secret, (PKCE_INFO + "|" + nonce).getBytes(StandardCharsets.UTF_8));
    return B64URL.encodeToString(digest);
  }

  private static byte[] normalizeSecretBytes(String secret) {
    if (secret == null) return new byte[0];
    String normalized = secret.trim();
    return normalized.getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] hmacSha256(byte[] key, byte[] message) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(message);
    } catch (Exception e) {
      throw new IllegalStateException("hmac error", e);
    }
  }
}
