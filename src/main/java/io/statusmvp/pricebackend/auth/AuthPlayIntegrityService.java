package io.statusmvp.pricebackend.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AuthPlayIntegrityService {
  private static final Logger log = LoggerFactory.getLogger(AuthPlayIntegrityService.class);
  private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};
  private static final String GOOGLE_OAUTH_AUDIENCE = "https://oauth2.googleapis.com/token";
  private static final String PLAY_INTEGRITY_SCOPE = "https://www.googleapis.com/auth/playintegrity";
  private static final String PLAY_INTEGRITY_DECODE_BASE_URL = "https://playintegrity.googleapis.com/v1/";
  private static final String PLAY_CHANNEL = "play";
  private static final List<String> ALLOWED_ANDROID_CHANNELS = List.of("play", "direct", "play_debug", "direct_debug");

  private final AuthProperties authProperties;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private volatile String cachedGoogleAccessToken;
  private volatile Instant cachedGoogleAccessTokenExpiresAt = Instant.EPOCH;

  public AuthPlayIntegrityService(AuthProperties authProperties, ObjectMapper objectMapper) {
    this.authProperties = authProperties;
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
  }

  public record PlayIntegrityHeaders(String distributionChannel, String token, String requestHash) {}

  public void requireAllowedProtectedAuthChannel(
      String platform,
      String distributionChannel,
      String operation) {
    String normalizedPlatform = normalizePlatform(platform);
    if (!"android".equals(normalizedPlatform)) {
      return;
    }

    String normalizedChannel = requireDistributionChannel(distributionChannel);
    List<String> allowedChannels = authProperties.getIntegrity().androidProtectedAuthAllowedChannelsList();
    if (allowedChannels.isEmpty()) {
      throw new AuthException(
          AuthErrorCode.DISTRIBUTION_CHANNEL_NOT_ALLOWED,
          "android protected auth channels not configured",
          503);
    }
    if (allowedChannels.contains(normalizedChannel)) {
      return;
    }

    log.warn(
        "blocked android protected auth because distribution channel is not allowed: channel={}, operation={}, allowedChannels={}",
        normalizedChannel,
        operation,
        allowedChannels);
    throw new AuthException(
        AuthErrorCode.DISTRIBUTION_CHANNEL_NOT_ALLOWED,
        "android distribution channel not allowed for protected auth",
        403);
  }

  public void verifyProtectedRequest(
      String deviceId,
      String platform,
      String method,
      String path,
      PlayIntegrityHeaders headers) {
    String normalizedPlatform = normalizePlatform(platform);
    if (!"android".equals(normalizedPlatform) || !authProperties.getIntegrity().isAndroidPlayIntegrityEnabled()) {
      return;
    }

    String normalizedChannel = requireDistributionChannel(headers == null ? null : headers.distributionChannel());
    if (!PLAY_CHANNEL.equals(normalizedChannel)) {
      return;
    }

    String normalizedDeviceId = requireDeviceId(deviceId);
    String normalizedMethod = normalizeMethod(method);
    String normalizedPath = normalizePath(path);
    String token = requireProtectedHeader(headers == null ? null : headers.token(), "play integrity token");
    String requestHash = requireProtectedHeader(headers == null ? null : headers.requestHash(), "play integrity request hash");
    String expectedRequestHash = expectedRequestHash(normalizedMethod, normalizedPath, normalizedDeviceId);
    if (!expectedRequestHash.equals(requestHash)) {
      throw new AuthException(AuthErrorCode.PLAY_INTEGRITY_INVALID, "play integrity request hash mismatch", 401);
    }

    requireServerConfiguration();
    Map<String, Object> payload = decodeIntegrityToken(token);
    verifyDecodedPayload(payload, expectedRequestHash);
    log.info(
        "play integrity verified: deviceId={}, method={}, path={}, channel={}, packageName={}",
        normalizedDeviceId,
        normalizedMethod,
        normalizedPath,
        normalizedChannel,
        expectedPackageName());
  }

  private void verifyDecodedPayload(Map<String, Object> payload, String expectedRequestHash) {
    Map<String, Object> requestDetails = requireMap(payload.get("requestDetails"), "play integrity requestDetails missing");
    String requestPackageName = stringValue(requestDetails.get("requestPackageName"));
    if (!expectedPackageName().equals(requestPackageName)) {
      throw new AuthException(AuthErrorCode.PLAY_INTEGRITY_INVALID, "play integrity package mismatch", 401);
    }
    String actualRequestHash = stringValue(requestDetails.get("requestHash"));
    if (!expectedRequestHash.equals(actualRequestHash)) {
      throw new AuthException(AuthErrorCode.PLAY_INTEGRITY_INVALID, "play integrity request hash invalid", 401);
    }
    long timestampMillis = parseRequestTimestampMillis(requestDetails);
    long allowedSkewMs = Math.max(30_000L, authProperties.getIntegrity().getAndroidPlayIntegrityFreshnessSeconds() * 1000L);
    long now = Instant.now().toEpochMilli();
    if (timestampMillis <= 0 || Math.abs(now - timestampMillis) > allowedSkewMs) {
      throw new AuthException(AuthErrorCode.PLAY_INTEGRITY_EXPIRED, "play integrity verdict expired", 401);
    }

    Map<String, Object> appIntegrity = requireMap(payload.get("appIntegrity"), "play integrity appIntegrity missing");
    String appRecognitionVerdict = stringValue(appIntegrity.get("appRecognitionVerdict"));
    if (!"PLAY_RECOGNIZED".equals(appRecognitionVerdict)) {
      throw new AuthException(AuthErrorCode.PLAY_INTEGRITY_INVALID, "play integrity app not recognized by Play", 401);
    }
    String appIntegrityPackageName = stringValue(appIntegrity.get("packageName"));
    if (StringUtils.hasText(appIntegrityPackageName) && !expectedPackageName().equals(appIntegrityPackageName)) {
      throw new AuthException(AuthErrorCode.PLAY_INTEGRITY_INVALID, "play integrity app package invalid", 401);
    }

    Map<String, Object> accountDetails = requireMap(payload.get("accountDetails"), "play integrity accountDetails missing");
    String appLicensingVerdict = stringValue(accountDetails.get("appLicensingVerdict"));
    if (!"LICENSED".equals(appLicensingVerdict)) {
      throw new AuthException(AuthErrorCode.PLAY_INTEGRITY_INVALID, "play integrity license invalid", 401);
    }

    Map<String, Object> deviceIntegrity = requireMap(payload.get("deviceIntegrity"), "play integrity deviceIntegrity missing");
    List<String> deviceVerdicts = stringList(deviceIntegrity.get("deviceRecognitionVerdict"));
    if (!deviceVerdicts.contains("MEETS_DEVICE_INTEGRITY")) {
      throw new AuthException(AuthErrorCode.PLAY_INTEGRITY_INVALID, "play integrity device verdict invalid", 401);
    }
  }

  private Map<String, Object> decodeIntegrityToken(String token) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(PLAY_INTEGRITY_DECODE_BASE_URL + expectedPackageName() + ":decodeIntegrityToken"))
              .timeout(Duration.ofSeconds(15))
              .header("authorization", "Bearer " + obtainGoogleAccessToken())
              .header("content-type", "application/json")
              .POST(
                  HttpRequest.BodyPublishers.ofString(
                      objectMapper.writeValueAsString(Map.of("integrity_token", token))))
              .build();
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        log.warn(
            "play integrity decode failed status={} body={}",
            response.statusCode(),
            truncate(response.body()));
        throw new AuthException(AuthErrorCode.PLAY_INTEGRITY_INVALID, "play integrity decode failed", 401);
      }
      Map<String, Object> decoded = objectMapper.readValue(response.body(), MAP_TYPE);
      return extractPayload(decoded);
    } catch (AuthException e) {
      throw e;
    } catch (Exception e) {
      log.warn("play integrity decode exception: {}", e.getMessage());
      throw new AuthException(AuthErrorCode.PLAY_INTEGRITY_INVALID, "play integrity verification failed", 401);
    }
  }

  private synchronized String obtainGoogleAccessToken() throws Exception {
    Instant now = Instant.now();
    if (cachedGoogleAccessToken != null && now.isBefore(cachedGoogleAccessTokenExpiresAt.minusSeconds(60))) {
      return cachedGoogleAccessToken;
    }

    String serviceAccountEmail = normalizedServiceAccountEmail();
    String serviceAccountPrivateKeyPem = normalizedServiceAccountPrivateKeyPem();
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(serviceAccountEmail)
            .subject(serviceAccountEmail)
            .audience(GOOGLE_OAUTH_AUDIENCE)
            .claim("scope", PLAY_INTEGRITY_SCOPE)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plus(55, ChronoUnit.MINUTES)))
            .build();
    SignedJWT signedJwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).build(),
            claims);
    signedJwt.sign(new RSASSASigner(parseRsaPrivateKey(serviceAccountPrivateKeyPem)));

    String formBody =
        "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
            + "&assertion=" + URLEncoder.encode(signedJwt.serialize(), StandardCharsets.UTF_8);
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(GOOGLE_OAUTH_AUDIENCE))
            .timeout(Duration.ofSeconds(15))
            .header("content-type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(formBody))
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() / 100 != 2) {
      throw new IllegalStateException("play integrity oauth failed: " + response.statusCode());
    }

    Map<String, Object> body = objectMapper.readValue(response.body(), MAP_TYPE);
    String accessToken = stringValue(body.get("access_token"));
    Number expiresIn = body.get("expires_in") instanceof Number ? (Number) body.get("expires_in") : 3600;
    if (!StringUtils.hasText(accessToken)) {
      throw new IllegalStateException("play integrity oauth returned empty access token");
    }
    cachedGoogleAccessToken = accessToken;
    cachedGoogleAccessTokenExpiresAt = now.plusSeconds(Math.max(60L, expiresIn.longValue()));
    return accessToken;
  }

  private Map<String, Object> extractPayload(Map<String, Object> decoded) {
    Object externalPayload = decoded.get("tokenPayloadExternal");
    if (externalPayload instanceof Map<?, ?> mapValue) {
      return castMap(mapValue);
    }
    if (externalPayload instanceof String serialized && StringUtils.hasText(serialized)) {
      try {
        return objectMapper.readValue(serialized, MAP_TYPE);
      } catch (Exception e) {
        throw new AuthException(AuthErrorCode.PLAY_INTEGRITY_INVALID, "play integrity payload invalid", 401);
      }
    }
    if (decoded.containsKey("requestDetails")) {
      return decoded;
    }
    throw new AuthException(AuthErrorCode.PLAY_INTEGRITY_INVALID, "play integrity payload missing", 401);
  }

  private String expectedPackageName() {
    String packageName = normalizeToken(authProperties.getIntegrity().getAndroidPlayIntegrityPackageName());
    if (packageName.isEmpty()) {
      throw new AuthException(AuthErrorCode.PLAY_INTEGRITY_INVALID, "play integrity package config missing", 503);
    }
    return packageName;
  }

  private void requireServerConfiguration() {
    if (normalizedServiceAccountEmail().isEmpty() || normalizedServiceAccountPrivateKeyPem().isEmpty()) {
      throw new AuthException(AuthErrorCode.PLAY_INTEGRITY_INVALID, "play integrity server config missing", 503);
    }
  }

  private String normalizedServiceAccountEmail() {
    return normalizeToken(authProperties.getIntegrity().getAndroidPlayIntegrityServiceAccountEmail());
  }

  private String normalizedServiceAccountPrivateKeyPem() {
    return normalizePem(authProperties.getIntegrity().getAndroidPlayIntegrityServiceAccountPrivateKeyPem());
  }

  private long parseRequestTimestampMillis(Map<String, Object> requestDetails) {
    Object timestampMillis = requestDetails.get("timestampMillis");
    if (timestampMillis instanceof Number numberValue) {
      return numberValue.longValue();
    }
    if (timestampMillis instanceof String stringValue && StringUtils.hasText(stringValue)) {
      try {
        return Long.parseLong(stringValue.trim());
      } catch (NumberFormatException ignored) {
        // Fall through to requestTime parsing.
      }
    }
    Object requestTime = requestDetails.get("requestTime");
    if (requestTime instanceof String isoTime && StringUtils.hasText(isoTime)) {
      try {
        return Instant.parse(isoTime.trim()).toEpochMilli();
      } catch (Exception ignored) {
        return 0;
      }
    }
    return 0;
  }

  private String expectedRequestHash(String method, String path, String deviceId) {
    return AuthUtils.sha256Base64Url("v1\n" + method + "\n" + path + "\n" + deviceId);
  }

  private static String requireDistributionChannel(String value) {
    String normalized = normalizeToken(value).toLowerCase(Locale.ROOT);
    if (!StringUtils.hasText(normalized)) {
      throw new AuthException(AuthErrorCode.PLAY_INTEGRITY_REQUIRED, "android distribution channel missing", 401);
    }
    if (!ALLOWED_ANDROID_CHANNELS.contains(normalized)) {
      throw new AuthException(AuthErrorCode.PLAY_INTEGRITY_INVALID, "android distribution channel invalid", 401);
    }
    return normalized;
  }

  private static String requireProtectedHeader(String value, String label) {
    String normalized = normalizeToken(value);
    if (normalized.isEmpty()) {
      throw new AuthException(AuthErrorCode.PLAY_INTEGRITY_REQUIRED, label + " missing", 401);
    }
    return normalized;
  }

  private static String requireDeviceId(String deviceId) {
    String normalized = normalizeToken(deviceId);
    if (normalized.isEmpty()) {
      throw new AuthException(AuthErrorCode.UNAUTHORIZED, "missing device id", 401);
    }
    return normalized;
  }

  private static String normalizeMethod(String method) {
    String normalized = normalizeToken(method).toUpperCase(Locale.ROOT);
    if (normalized.isEmpty()) {
      throw new AuthException(AuthErrorCode.BAD_REQUEST, "invalid request method", 400);
    }
    return normalized;
  }

  private static String normalizePath(String path) {
    String normalized = normalizeToken(path);
    if (!normalized.startsWith("/")) {
      throw new AuthException(AuthErrorCode.BAD_REQUEST, "invalid request path", 400);
    }
    int queryIndex = normalized.indexOf('?');
    return queryIndex >= 0 ? normalized.substring(0, queryIndex) : normalized;
  }

  private static String normalizePlatform(String platform) {
    return normalizeToken(platform).toLowerCase(Locale.ROOT);
  }

  private static Map<String, Object> requireMap(Object value, String message) {
    if (value instanceof Map<?, ?> mapValue) {
      return castMap(mapValue);
    }
    throw new AuthException(AuthErrorCode.PLAY_INTEGRITY_INVALID, message, 401);
  }

  private static Map<String, Object> castMap(Map<?, ?> raw) {
    LinkedHashMap<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : raw.entrySet()) {
      out.put(String.valueOf(entry.getKey()), entry.getValue());
    }
    return out;
  }

  private static List<String> stringList(Object value) {
    if (!(value instanceof List<?> listValue)) {
      return List.of();
    }
    return listValue.stream()
        .map(String::valueOf)
        .map(String::trim)
        .filter(StringUtils::hasText)
        .toList();
  }

  private static String stringValue(Object value) {
    if (value == null) {
      return "";
    }
    return String.valueOf(value).trim();
  }

  private static String normalizeToken(String value) {
    if (value == null) {
      return "";
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? "" : trimmed;
  }

  private static String normalizePem(String value) {
    String normalized = normalizeToken(value);
    if (normalized.isEmpty()) {
      return "";
    }
    return normalized.replace("\\n", "\n").replace("\r\n", "\n").replace('\r', '\n');
  }

  private static String truncate(String value) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.length() <= 300) {
      return normalized;
    }
    return normalized.substring(0, 300) + "...";
  }

  private static RSAPrivateKey parseRsaPrivateKey(String pem) throws Exception {
    byte[] encoded = decodePem(pem);
    return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded));
  }

  private static byte[] decodePem(String pem) {
    String normalized = normalizePem(pem);
    String sanitized =
        normalized
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s+", "");
    if (sanitized.isBlank()) {
      throw new IllegalArgumentException("Missing private key content");
    }
    return Base64.getDecoder().decode(sanitized);
  }
}
