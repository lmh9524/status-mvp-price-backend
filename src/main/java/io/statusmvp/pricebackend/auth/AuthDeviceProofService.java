package io.statusmvp.pricebackend.auth;

import io.statusmvp.pricebackend.auth.dto.AuthDtos;
import io.statusmvp.pricebackend.auth.model.DeviceProofChallengeRecord;
import io.statusmvp.pricebackend.auth.model.DeviceProofRegistrationRecord;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AuthDeviceProofService {
  private static final Logger log = LoggerFactory.getLogger(AuthDeviceProofService.class);
  private static final Base64.Decoder B64URL_DECODER = Base64.getUrlDecoder();
  private static final Base64.Encoder B64URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

  private final AuthProperties authProperties;
  private final AuthRedisStore store;

  public AuthDeviceProofService(AuthProperties authProperties, AuthRedisStore store) {
    this.authProperties = authProperties;
    this.store = store;
  }

  public record DeviceProofHeaders(
      String challengeId,
      String keyId,
      String signatureBase64Url,
      String publicKeyBase64Url,
      String capability) {}

  public record VerifiedDeviceProof(String keyId, String platform, String capability) {}

  public AuthDtos.DeviceProofChallengeResponse issueChallenge(
      AuthDtos.DeviceProofChallengeRequest request, String deviceId, String platform) {
    String normalizedDeviceId = requireDeviceId(deviceId);
    String normalizedPlatform = normalizePlatform(platform);
    String normalizedMethod = normalizeMethod(request.method());
    String normalizedPath = normalizePath(request.path());
    long ttlSeconds = Math.max(15, authProperties.getIntegrity().getDeviceProofChallengeTtlSeconds());
    long now = now();
    String challengeId = AuthUtils.randomBase64Url(24);
    String challenge = AuthUtils.randomBase64Url(32);

    store.putDeviceProofChallenge(
        new DeviceProofChallengeRecord(
            challengeId,
            challenge,
            normalizedDeviceId,
            normalizedMethod,
            normalizedPath,
            now,
            now + ttlSeconds * 1000),
        ttlSeconds);
    log.info(
        "device proof challenge issued: deviceId={}, platform={}, method={}, path={}, expiresAt={}",
        normalizedDeviceId,
        normalizedPlatform,
        normalizedMethod,
        normalizedPath,
        Instant.ofEpochMilli(now + ttlSeconds * 1000));
    return new AuthDtos.DeviceProofChallengeResponse(challengeId, challenge, ttlSeconds);
  }

  public VerifiedDeviceProof verifyProtectedRequest(
      String deviceId,
      String platform,
      String method,
      String path,
      DeviceProofHeaders headers) {
    String normalizedPlatform = normalizePlatform(platform);
    if (!requiresProof(normalizedPlatform, deviceId)) {
      return null;
    }

    String normalizedDeviceId = requireDeviceId(deviceId);
    String normalizedMethod = normalizeMethod(method);
    String normalizedPath = normalizePath(path);
    String challengeId = requireHeader(headers == null ? null : headers.challengeId(), "device proof challenge id");
    String requestKeyId = requireHeader(headers == null ? null : headers.keyId(), "device proof key id");
    String signatureBase64Url =
        requireHeader(headers == null ? null : headers.signatureBase64Url(), "device proof signature");
    String requestPublicKeyBase64Url = emptyToNull(headers == null ? null : headers.publicKeyBase64Url());
    String capability = emptyToNull(headers == null ? null : headers.capability());

    DeviceProofChallengeRecord challenge =
        store
            .consumeDeviceProofChallenge(challengeId)
            .orElseThrow(
                () ->
                    new AuthException(
                        AuthErrorCode.DEVICE_PROOF_INVALID, "device proof challenge invalid", 401));

    long now = now();
    if (challenge.expiresAt() < now) {
      throw new AuthException(AuthErrorCode.DEVICE_PROOF_EXPIRED, "device proof challenge expired", 401);
    }
    if (!normalizedDeviceId.equals(challenge.deviceId())) {
      throw new AuthException(AuthErrorCode.DEVICE_PROOF_INVALID, "device proof device mismatch", 401);
    }
    if (!normalizedMethod.equals(challenge.method()) || !normalizedPath.equals(challenge.path())) {
      throw new AuthException(AuthErrorCode.DEVICE_PROOF_INVALID, "device proof request mismatch", 401);
    }

    DeviceProofRegistrationRecord existing = store.getDeviceProofRegistration(normalizedDeviceId).orElse(null);
    String effectivePublicKeyBase64Url;
    long createdAt;
    String storedPlatform = normalizedPlatform;
    if (existing != null) {
      effectivePublicKeyBase64Url = existing.publicKeyBase64Url();
      createdAt = existing.createdAt();
      if ("unknown".equals(storedPlatform)) {
        storedPlatform = normalizePlatform(existing.platform());
      }
      if (!existing.keyId().equals(requestKeyId)) {
        throw new AuthException(AuthErrorCode.DEVICE_PROOF_INVALID, "device proof key mismatch", 401);
      }
      if (requestPublicKeyBase64Url != null && !requestPublicKeyBase64Url.equals(existing.publicKeyBase64Url())) {
        throw new AuthException(AuthErrorCode.DEVICE_PROOF_INVALID, "device proof public key mismatch", 401);
      }
    } else {
      effectivePublicKeyBase64Url =
          requireHeader(requestPublicKeyBase64Url, "device proof public key");
      createdAt = now;
    }

    String derivedKeyId = keyIdForPublicKey(effectivePublicKeyBase64Url);
    if (!derivedKeyId.equals(requestKeyId)) {
      throw new AuthException(AuthErrorCode.DEVICE_PROOF_INVALID, "device proof key id invalid", 401);
    }
    verifySignature(challenge.challenge(), effectivePublicKeyBase64Url, signatureBase64Url);

    store.putDeviceProofRegistration(
        new DeviceProofRegistrationRecord(
            normalizedDeviceId,
            requestKeyId,
            effectivePublicKeyBase64Url,
            storedPlatform,
            createdAt,
            now));
    log.info(
        "device proof verified: deviceId={}, platform={}, method={}, path={}, keyId={}, capability={}",
        normalizedDeviceId,
        storedPlatform,
        normalizedMethod,
        normalizedPath,
        requestKeyId,
        capability == null ? "" : capability);
    return new VerifiedDeviceProof(requestKeyId, storedPlatform, capability);
  }

  public boolean requiresProof(String platform, String deviceId) {
    String normalizedPlatform = normalizePlatform(platform);
    if ("ios".equals(normalizedPlatform)) {
      return authProperties.getIntegrity().isIosDeviceProofEnabled();
    }
    if ("android".equals(normalizedPlatform)) {
      return authProperties.getIntegrity().isAndroidDeviceProofEnabled();
    }
    String normalizedDeviceId = emptyToNull(deviceId);
    if (normalizedDeviceId == null) {
      return false;
    }
    return store
        .getDeviceProofRegistration(normalizedDeviceId)
        .map(DeviceProofRegistrationRecord::platform)
        .map(AuthDeviceProofService::normalizePlatform)
        .filter(
            registeredPlatform ->
                ("ios".equals(registeredPlatform) && authProperties.getIntegrity().isIosDeviceProofEnabled())
                    || ("android".equals(registeredPlatform)
                        && authProperties.getIntegrity().isAndroidDeviceProofEnabled()))
        .isPresent();
  }

  private static void verifySignature(
      String challenge, String publicKeyBase64Url, String signatureBase64Url) {
    try {
      PublicKey publicKey = publicKeyFromBase64Url(publicKeyBase64Url);
      Signature verifier = Signature.getInstance("SHA256withECDSA");
      verifier.initVerify(publicKey);
      verifier.update(challenge.getBytes(StandardCharsets.UTF_8));
      if (!verifier.verify(decodeBase64Url(signatureBase64Url))) {
        throw new AuthException(AuthErrorCode.DEVICE_PROOF_INVALID, "device proof signature invalid", 401);
      }
    } catch (AuthException e) {
      throw e;
    } catch (Exception e) {
      throw new AuthException(AuthErrorCode.DEVICE_PROOF_INVALID, "device proof verification failed", 401);
    }
  }

  private static PublicKey publicKeyFromBase64Url(String publicKeyBase64Url) throws Exception {
    byte[] raw = decodeBase64Url(publicKeyBase64Url);
    if (raw.length != 65 || raw[0] != 0x04) {
      throw new IllegalArgumentException("device proof public key format invalid");
    }

    byte[] xBytes = new byte[32];
    byte[] yBytes = new byte[32];
    System.arraycopy(raw, 1, xBytes, 0, 32);
    System.arraycopy(raw, 33, yBytes, 0, 32);

    AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
    parameters.init(new ECGenParameterSpec("secp256r1"));
    ECParameterSpec ecParameterSpec = parameters.getParameterSpec(ECParameterSpec.class);
    ECPoint point = new ECPoint(new BigInteger(1, xBytes), new BigInteger(1, yBytes));
    ECPublicKeySpec publicKeySpec = new ECPublicKeySpec(point, ecParameterSpec);
    return KeyFactory.getInstance("EC").generatePublic(publicKeySpec);
  }

  private static String keyIdForPublicKey(String publicKeyBase64Url) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(decodeBase64Url(publicKeyBase64Url));
      return B64URL_ENCODER.encodeToString(hash);
    } catch (Exception e) {
      throw new AuthException(AuthErrorCode.DEVICE_PROOF_INVALID, "device proof key id invalid", 401);
    }
  }

  private static byte[] decodeBase64Url(String value) {
    try {
      String normalized = value;
      int mod = normalized.length() % 4;
      if (mod > 0) {
        normalized = normalized + "=".repeat(4 - mod);
      }
      return B64URL_DECODER.decode(normalized);
    } catch (IllegalArgumentException e) {
      throw new AuthException(AuthErrorCode.DEVICE_PROOF_INVALID, "device proof value invalid", 401);
    }
  }

  private static String requireHeader(String value, String label) {
    String normalized = emptyToNull(value);
    if (normalized == null) {
      throw new AuthException(AuthErrorCode.DEVICE_PROOF_REQUIRED, label + " missing", 401);
    }
    return normalized;
  }

  private static String requireDeviceId(String deviceId) {
    String normalized = emptyToNull(deviceId);
    if (normalized == null) {
      throw new AuthException(AuthErrorCode.UNAUTHORIZED, "missing device id", 401);
    }
    return normalized;
  }

  private static String normalizeMethod(String method) {
    String normalized = emptyToNull(method);
    if (normalized == null) {
      throw new AuthException(AuthErrorCode.BAD_REQUEST, "device proof method missing", 400);
    }
    return normalized.toUpperCase(Locale.ROOT);
  }

  private static String normalizePath(String path) {
    String normalized = emptyToNull(path);
    if (normalized == null || !normalized.startsWith("/")) {
      throw new AuthException(AuthErrorCode.BAD_REQUEST, "device proof path invalid", 400);
    }
    int queryIndex = normalized.indexOf('?');
    String out = queryIndex >= 0 ? normalized.substring(0, queryIndex) : normalized;
    if (out.length() > 1 && out.endsWith("/")) {
      out = out.substring(0, out.length() - 1);
    }
    return out;
  }

  private static String normalizePlatform(String platform) {
    String normalized = emptyToNull(platform);
    if (normalized == null) {
      return "unknown";
    }
    return normalized.toLowerCase(Locale.ROOT);
  }

  private static String emptyToNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return value.trim();
  }

  private static long now() {
    return System.currentTimeMillis();
  }
}
