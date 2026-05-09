package io.statusmvp.pricebackend.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypted, short-lived resume tokens for X OAuth.
 *
 * <p>Used when X userinfo is temporarily unavailable (e.g., 503). The backend can return a resume
 * token to the app so it can retry {@code /api/v1/auth/x/resume} without forcing the user to
 * re-open the browser and re-authorize.
 *
 * <p>Token confidentiality is required because it contains the X access token.
 */
public final class XOAuthResumeTokens {
  private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder B64URL_DEC = Base64.getUrlDecoder();
  private static final SecureRandom RNG = new SecureRandom();

  private static final int VERSION = 2;
  private static final int IV_BYTES_LEN = 12;
  private static final int TAG_BITS = 128;
  private static final long MAX_FUTURE_IAT_SKEW_SECONDS = 60;
  private static final String AAD = "x-resume-v2";

  private XOAuthResumeTokens() {}

  private record Payload(int v, String did, String dpk, long iat, long exp, String at) {}

  public record Parsed(
      String deviceId, String deviceProofKeyId, String accessToken, long issuedAtMs, long expiresAtMs) {}

  public static String issue(
      ObjectMapper objectMapper,
      String secret,
      String deviceId,
      String deviceProofKeyId,
      String accessToken,
      long ttlSeconds,
      long nowMs) {
    byte[] secretBytes = normalizeSecretBytes(secret);
    if (secretBytes.length < 32) {
      throw new IllegalArgumentException("x.stateSecret must be at least 32 bytes");
    }

    long iatSec = Math.max(0, nowMs / 1000);
    long expSec = iatSec + Math.max(1, ttlSeconds);
    Payload payload = new Payload(VERSION, emptyToNull(deviceId), emptyToNull(deviceProofKeyId), iatSec, expSec, accessToken);

    byte[] payloadBytes;
    try {
      payloadBytes = objectMapper.writeValueAsBytes(payload);
    } catch (Exception e) {
      throw new IllegalStateException("resume token serialization error", e);
    }

    byte[] iv = new byte[IV_BYTES_LEN];
    RNG.nextBytes(iv);

    byte[] cipherBytes = aesGcmEncrypt(deriveAesKey(secretBytes), iv, payloadBytes);
    byte[] out = new byte[1 + iv.length + cipherBytes.length];
    out[0] = (byte) VERSION;
    System.arraycopy(iv, 0, out, 1, iv.length);
    System.arraycopy(cipherBytes, 0, out, 1 + iv.length, cipherBytes.length);
    return B64URL.encodeToString(out);
  }

  public static Parsed parseAndDecrypt(
      ObjectMapper objectMapper, String secret, String token, long nowMs) {
    if (token == null || token.isBlank()) return null;
    byte[] raw;
    try {
      raw = B64URL_DEC.decode(token.trim());
    } catch (IllegalArgumentException e) {
      return null;
    }

    if (raw.length < 1 + IV_BYTES_LEN + 1) return null;
    int ver = raw[0] & 0xff;
    if (ver != VERSION) return null;

    byte[] iv = new byte[IV_BYTES_LEN];
    System.arraycopy(raw, 1, iv, 0, iv.length);
    byte[] cipherBytes = new byte[raw.length - 1 - iv.length];
    System.arraycopy(raw, 1 + iv.length, cipherBytes, 0, cipherBytes.length);

    byte[] secretBytes = normalizeSecretBytes(secret);
    if (secretBytes.length < 32) return null;

    byte[] payloadBytes;
    try {
      payloadBytes = aesGcmDecrypt(deriveAesKey(secretBytes), iv, cipherBytes);
    } catch (Exception e) {
      return null;
    }

    Payload payload;
    try {
      payload = objectMapper.readValue(payloadBytes, Payload.class);
    } catch (Exception e) {
      return null;
    }
    if (payload == null || payload.v != VERSION) return null;
    if (payload.at == null || payload.at.isBlank()) return null;

    long nowSec = Math.max(0, nowMs / 1000);
    if (payload.iat > nowSec + MAX_FUTURE_IAT_SKEW_SECONDS) return null;
    if (payload.exp <= nowSec) return null;
    if (payload.exp < payload.iat) return null;

    long issuedAtMs = payload.iat * 1000;
    long expiresAtMs = payload.exp * 1000;
    return new Parsed(payload.did, payload.dpk, payload.at, issuedAtMs, expiresAtMs);
  }

  private static SecretKeySpec deriveAesKey(byte[] secretBytes) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] key = digest.digest(secretBytes);
      return new SecretKeySpec(key, "AES");
    } catch (Exception e) {
      throw new IllegalStateException("key derivation error", e);
    }
  }

  private static byte[] aesGcmEncrypt(SecretKeySpec key, byte[] iv, byte[] plaintext) {
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      cipher.updateAAD(AAD.getBytes(StandardCharsets.US_ASCII));
      return cipher.doFinal(plaintext);
    } catch (Exception e) {
      throw new IllegalStateException("aes-gcm encrypt error", e);
    }
  }

  private static byte[] aesGcmDecrypt(SecretKeySpec key, byte[] iv, byte[] ciphertext) throws Exception {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
    cipher.updateAAD(AAD.getBytes(StandardCharsets.US_ASCII));
    return cipher.doFinal(ciphertext);
  }

  private static byte[] normalizeSecretBytes(String secret) {
    if (secret == null) return new byte[0];
    String normalized = secret.trim();
    return normalized.getBytes(StandardCharsets.UTF_8);
  }

  private static String emptyToNull(String value) {
    if (value == null) return null;
    String v = value.trim();
    return v.isEmpty() ? null : v;
  }
}
