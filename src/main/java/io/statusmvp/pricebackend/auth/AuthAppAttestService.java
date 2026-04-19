package io.statusmvp.pricebackend.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import io.statusmvp.pricebackend.auth.model.AppAttestAssertionChallengeRecord;
import io.statusmvp.pricebackend.auth.dto.AuthDtos;
import io.statusmvp.pricebackend.auth.model.AppAttestChallengeRecord;
import io.statusmvp.pricebackend.auth.model.AppAttestRegistrationRecord;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AuthAppAttestService {
  private static final Logger log = LoggerFactory.getLogger(AuthAppAttestService.class);
  private static final ObjectMapper CBOR_MAPPER = new ObjectMapper(new CBORFactory());
  private static final TypeReference<LinkedHashMap<Object, Object>> CBOR_MAP_TYPE = new TypeReference<>() {};
  private static final Base64.Decoder B64URL_DECODER = Base64.getUrlDecoder();
  private static final Base64.Encoder B64URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final String APPLE_APP_ATTEST_ROOT_CA_RESOURCE = "apple/Apple_App_Attestation_Root_CA.pem";
  private static final String NONCE_EXTENSION_OID = "1.2.840.113635.100.8.2";
  private static final String APPLE_APP_ATTEST_FORMAT = "apple-appattest";

  private final AuthProperties authProperties;
  private final AuthRedisStore store;
  private final X509Certificate rootCertificate;

  public AuthAppAttestService(AuthProperties authProperties, AuthRedisStore store) {
    this.authProperties = authProperties;
    this.store = store;
    this.rootCertificate = loadRootCertificate();
  }

  public record AppAttestHeaders(
      String challengeId, String keyId, String assertionObjectBase64Url, String capability) {}

  public AuthDtos.AppAttestChallengeResponse issueChallenge(
      AuthDtos.AppAttestChallengeRequest request, String deviceId, String platform) {
    if (!requiresAppAttest(platform)) {
      return new AuthDtos.AppAttestChallengeResponse(false, null, null, 0);
    }

    String normalizedDeviceId = requireDeviceId(deviceId);
    String requestedKeyId = requireNonEmpty(request == null ? null : request.keyId(), "app attest key id");
    AppAttestRegistrationRecord existing = store.getAppAttestRegistration(normalizedDeviceId).orElse(null);
    if (existing != null
        && requestedKeyId.equals(existing.keyId())
        && requireAllowedApplicationIdentifiers().contains(existing.applicationIdentifier())) {
      log.info(
          "app attest challenge skipped because registration already exists: deviceId={}, keyId={}",
          normalizedDeviceId,
          requestedKeyId);
      return new AuthDtos.AppAttestChallengeResponse(false, null, null, 0);
    }

    long ttlSeconds = Math.max(15, authProperties.getIntegrity().getAppAttestChallengeTtlSeconds());
    long now = now();
    String challengeId = AuthUtils.randomBase64Url(24);
    String challenge = AuthUtils.randomBase64Url(32);
    store.putAppAttestChallenge(
        new AppAttestChallengeRecord(
            challengeId,
            challenge,
            normalizedDeviceId,
            requestedKeyId,
            now,
            now + ttlSeconds * 1000),
        ttlSeconds);
    log.info(
        "app attest registration challenge issued: deviceId={}, keyId={}, expiresAt={}",
        normalizedDeviceId,
        requestedKeyId,
        Instant.ofEpochMilli(now + ttlSeconds * 1000));
    return new AuthDtos.AppAttestChallengeResponse(true, challengeId, challenge, ttlSeconds);
  }

  public AuthDtos.AppAttestRegisterResponse registerAttestation(
      AuthDtos.AppAttestRegisterRequest request, String deviceId, String platform) {
    if (!requiresAppAttest(platform)) {
      return new AuthDtos.AppAttestRegisterResponse(false, request == null ? null : request.keyId());
    }

    String normalizedDeviceId = requireDeviceId(deviceId);
    String challengeId = requireNonEmpty(request == null ? null : request.challengeId(), "app attest challenge id");
    String requestedKeyId = requireNonEmpty(request == null ? null : request.keyId(), "app attest key id");
    String attestationObjectBase64Url =
        requireNonEmpty(
            request == null ? null : request.attestationObjectBase64Url(), "app attest attestation object");
    String capability = emptyToNull(request == null ? null : request.capability());

    AppAttestChallengeRecord challenge =
        store
            .consumeAppAttestChallenge(challengeId)
            .orElseThrow(
                () -> new AuthException(AuthErrorCode.APP_ATTEST_INVALID, "app attest challenge invalid", 401));

    long now = now();
    if (challenge.expiresAt() < now) {
      throw new AuthException(AuthErrorCode.APP_ATTEST_EXPIRED, "app attest challenge expired", 401);
    }
    if (!normalizedDeviceId.equals(challenge.deviceId())) {
      throw new AuthException(AuthErrorCode.APP_ATTEST_INVALID, "app attest device mismatch", 401);
    }
    if (!requestedKeyId.equals(challenge.keyId())) {
      throw new AuthException(AuthErrorCode.APP_ATTEST_INVALID, "app attest key mismatch", 401);
    }

    VerifiedAttestation verified =
        verifyAttestation(
            requestedKeyId,
            challenge.challenge(),
            attestationObjectBase64Url,
            requireAllowedApplicationIdentifiers());

    AppAttestRegistrationRecord existing = store.getAppAttestRegistration(normalizedDeviceId).orElse(null);
    long createdAt = existing == null ? now : existing.createdAt();
    store.putAppAttestRegistration(
        new AppAttestRegistrationRecord(
            normalizedDeviceId,
            requestedKeyId,
            verified.applicationIdentifier(),
            verified.publicKeySpkiBase64Url(),
            verified.credentialIdBase64Url(),
            verified.receiptBase64Url(),
            capability,
            createdAt,
            now,
            0));
    log.info(
        "app attest registration verified: deviceId={}, keyId={}, applicationIdentifier={}, capability={}",
        normalizedDeviceId,
        requestedKeyId,
        verified.applicationIdentifier(),
        capability == null ? "" : capability);
    return new AuthDtos.AppAttestRegisterResponse(true, requestedKeyId);
  }

  public AuthDtos.AppAttestAssertionChallengeResponse issueAssertionChallenge(
      AuthDtos.AppAttestAssertionChallengeRequest request, String deviceId, String platform) {
    if (!requiresAppAttest(platform)) {
      throw new AuthException(AuthErrorCode.BAD_REQUEST, "app attest assertion unsupported for platform", 400);
    }

    String normalizedDeviceId = requireDeviceId(deviceId);
    String requestedKeyId = requireNonEmpty(request == null ? null : request.keyId(), "app attest key id");
    String normalizedMethod = normalizeMethod(request == null ? null : request.method());
    String normalizedPath = normalizePath(request == null ? null : request.path());
    AppAttestRegistrationRecord registration = requireRegistration(normalizedDeviceId);
    if (!requestedKeyId.equals(registration.keyId())) {
      throw new AuthException(AuthErrorCode.APP_ATTEST_INVALID, "app attest key mismatch", 401);
    }

    long ttlSeconds = Math.max(15, authProperties.getIntegrity().getAppAttestAssertionChallengeTtlSeconds());
    long now = now();
    String challengeId = AuthUtils.randomBase64Url(24);
    String challenge = AuthUtils.randomBase64Url(32);
    store.putAppAttestAssertionChallenge(
        new AppAttestAssertionChallengeRecord(
            challengeId,
            challenge,
            normalizedDeviceId,
            requestedKeyId,
            normalizedMethod,
            normalizedPath,
            now,
            now + ttlSeconds * 1000),
        ttlSeconds);
    log.info(
        "app attest assertion challenge issued: deviceId={}, keyId={}, method={}, path={}, expiresAt={}",
        normalizedDeviceId,
        requestedKeyId,
        normalizedMethod,
        normalizedPath,
        Instant.ofEpochMilli(now + ttlSeconds * 1000));
    return new AuthDtos.AppAttestAssertionChallengeResponse(challengeId, challenge, ttlSeconds);
  }

  public void ensureProtectedRequestAllowed(String deviceId, String platform) {
    if (!requiresAppAttest(platform)) {
      return;
    }

    requireRegistration(requireDeviceId(deviceId));
  }

  public void verifyProtectedRequest(
      String deviceId, String platform, String method, String path, AppAttestHeaders headers) {
    if (!requiresAppAttest(platform)) {
      return;
    }

    String normalizedDeviceId = requireDeviceId(deviceId);
    String normalizedMethod = normalizeMethod(method);
    String normalizedPath = normalizePath(path);
    String challengeId = requireProtectedHeader(headers == null ? null : headers.challengeId(), "app attest challenge id");
    String requestKeyId = requireProtectedHeader(headers == null ? null : headers.keyId(), "app attest key id");
    String assertionObjectBase64Url =
        requireProtectedHeader(headers == null ? null : headers.assertionObjectBase64Url(), "app attest assertion");
    String capability = emptyToNull(headers == null ? null : headers.capability());

    AppAttestAssertionChallengeRecord challenge =
        store
            .consumeAppAttestAssertionChallenge(challengeId)
            .orElseThrow(
                () -> new AuthException(AuthErrorCode.APP_ATTEST_INVALID, "app attest assertion challenge invalid", 401));

    long now = now();
    if (challenge.expiresAt() < now) {
      throw new AuthException(AuthErrorCode.APP_ATTEST_EXPIRED, "app attest assertion challenge expired", 401);
    }
    if (!normalizedDeviceId.equals(challenge.deviceId())) {
      throw new AuthException(AuthErrorCode.APP_ATTEST_INVALID, "app attest assertion device mismatch", 401);
    }
    if (!normalizedMethod.equals(challenge.method()) || !normalizedPath.equals(challenge.path())) {
      throw new AuthException(AuthErrorCode.APP_ATTEST_INVALID, "app attest assertion request mismatch", 401);
    }
    if (!requestKeyId.equals(challenge.keyId())) {
      throw new AuthException(AuthErrorCode.APP_ATTEST_INVALID, "app attest assertion key mismatch", 401);
    }

    AppAttestRegistrationRecord registration = requireRegistration(normalizedDeviceId);
    if (!requestKeyId.equals(registration.keyId())) {
      throw new AuthException(AuthErrorCode.APP_ATTEST_INVALID, "app attest registration key mismatch", 401);
    }

    VerifiedAssertion verified =
        verifyAssertion(challenge.challenge(), assertionObjectBase64Url, registration, requireAllowedApplicationIdentifiers());

    store.putAppAttestRegistration(
        new AppAttestRegistrationRecord(
            registration.deviceId(),
            registration.keyId(),
            verified.applicationIdentifier(),
            registration.publicKeySpkiBase64Url(),
            registration.credentialIdBase64Url(),
            registration.receiptBase64Url(),
            capability == null ? registration.capability() : capability,
            registration.createdAt(),
            now,
            verified.signCount()));
    log.info(
        "app attest assertion verified: deviceId={}, keyId={}, method={}, path={}, signCount={}, capability={}",
        normalizedDeviceId,
        requestKeyId,
        normalizedMethod,
        normalizedPath,
        verified.signCount(),
        capability == null ? "" : capability);
  }

  private VerifiedAttestation verifyAttestation(
      String keyId, String challenge, String attestationObjectBase64Url, List<String> allowedApplicationIdentifiers) {
    try {
      Map<Object, Object> payload = parseCborMap(decodeBase64Url(attestationObjectBase64Url));
      String format = stringValue(payload, "fmt");
      if (!APPLE_APP_ATTEST_FORMAT.equals(format)) {
        throw new AuthException(AuthErrorCode.APP_ATTEST_INVALID, "app attest format invalid", 401);
      }

      Map<Object, Object> attStmt = mapValue(payload, "attStmt");
      byte[] authData = bytesValue(payload, "authData");
      List<byte[]> x5cEntries = bytesListValue(attStmt, "x5c");
      if (x5cEntries.isEmpty()) {
        throw new AuthException(AuthErrorCode.APP_ATTEST_INVALID, "app attest certificate chain missing", 401);
      }
      List<X509Certificate> certificates = parseCertificateChain(x5cEntries);
      validateCertificateChain(certificates);

      byte[] expectedNonce = sha256(concat(authData, sha256(challenge.getBytes(StandardCharsets.UTF_8))));
      byte[] certificateNonce = extractNonce(certificates.get(0));
      if (!Arrays.equals(expectedNonce, certificateNonce)) {
        throw new AuthException(AuthErrorCode.APP_ATTEST_INVALID, "app attest nonce mismatch", 401);
      }

      ParsedAuthData parsedAuthData = parseAuthData(authData);
      String matchedApplicationIdentifier =
          matchApplicationIdentifier(parsedAuthData.applicationIdentifierHash(), allowedApplicationIdentifiers);
      String publicKeySpkiBase64Url = base64UrlEncode(cosePublicKeySpki(parsedAuthData.cosePublicKeyBytes()));
      byte[] receipt = optionalBytes(attStmt, "receipt");

      if (parsedAuthData.signCount() != 0) {
        log.warn(
            "app attest registration returned a non-zero sign count: keyId={}, signCount={}",
            keyId,
            parsedAuthData.signCount());
      }

      return new VerifiedAttestation(
          matchedApplicationIdentifier,
          publicKeySpkiBase64Url,
          base64UrlEncode(parsedAuthData.credentialId()),
          receipt == null ? "" : base64UrlEncode(receipt));
    } catch (AuthException e) {
      throw e;
    } catch (Exception e) {
      throw new AuthException(AuthErrorCode.APP_ATTEST_INVALID, "app attest verification failed", 401);
    }
  }

  private VerifiedAssertion verifyAssertion(
      String challenge,
      String assertionObjectBase64Url,
      AppAttestRegistrationRecord registration,
      List<String> allowedApplicationIdentifiers) {
    try {
      Map<Object, Object> payload = parseCborMap(decodeBase64Url(assertionObjectBase64Url));
      byte[] authenticatorData = bytesValue(payload, "authenticatorData");
      byte[] signature = bytesValue(payload, "signature");
      ParsedAssertionAuthData parsedAuthData = parseAssertionAuthData(authenticatorData);
      String matchedApplicationIdentifier =
          matchApplicationIdentifier(parsedAuthData.applicationIdentifierHash(), allowedApplicationIdentifiers);
      if (!matchedApplicationIdentifier.equals(registration.applicationIdentifier())) {
        throw new AuthException(AuthErrorCode.APP_ATTEST_INVALID, "app attest application identifier mismatch", 401);
      }
      if (parsedAuthData.signCount() <= 0) {
        throw new AuthException(AuthErrorCode.APP_ATTEST_INVALID, "app attest assertion counter invalid", 401);
      }
      if (parsedAuthData.signCount() <= Math.max(0, registration.assertionCounter())) {
        throw new AuthException(AuthErrorCode.APP_ATTEST_INVALID, "app attest assertion replay detected", 401);
      }

      byte[] clientDataHash = sha256(challenge.getBytes(StandardCharsets.UTF_8));
      verifyAssertionSignature(
          registration.publicKeySpkiBase64Url(), concat(authenticatorData, clientDataHash), signature);
      return new VerifiedAssertion(matchedApplicationIdentifier, parsedAuthData.signCount());
    } catch (AuthException e) {
      throw e;
    } catch (Exception e) {
      throw new AuthException(AuthErrorCode.APP_ATTEST_INVALID, "app attest assertion verification failed", 401);
    }
  }

  private static List<X509Certificate> parseCertificateChain(List<byte[]> x5cEntries) throws Exception {
    CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
    List<X509Certificate> certificates = new ArrayList<>(x5cEntries.size());
    for (byte[] raw : x5cEntries) {
      try (InputStream in = new java.io.ByteArrayInputStream(raw)) {
        certificates.add((X509Certificate) certificateFactory.generateCertificate(in));
      }
    }
    return certificates;
  }

  private void validateCertificateChain(List<X509Certificate> certificates) throws Exception {
    if (certificates.isEmpty()) {
      throw new AuthException(AuthErrorCode.APP_ATTEST_INVALID, "app attest certificate chain missing", 401);
    }
    CertPath certPath = CertificateFactory.getInstance("X.509").generateCertPath(certificates);
    PKIXParameters params = new PKIXParameters(java.util.Set.of(new TrustAnchor(rootCertificate, null)));
    params.setRevocationEnabled(false);
    CertPathValidator.getInstance("PKIX").validate(certPath, params);
  }

  private static ParsedAuthData parseAuthData(byte[] authData) {
    if (authData == null || authData.length < 55) {
      throw new AuthException(AuthErrorCode.APP_ATTEST_INVALID, "app attest authenticator data invalid", 401);
    }
    byte[] applicationIdentifierHash = Arrays.copyOfRange(authData, 0, 32);
    int flags = authData[32] & 0xFF;
    if ((flags & 0x40) == 0) {
      throw new AuthException(AuthErrorCode.APP_ATTEST_INVALID, "app attest credential data missing", 401);
    }
    long signCount =
        ((long) (authData[33] & 0xFF) << 24)
            | ((long) (authData[34] & 0xFF) << 16)
            | ((long) (authData[35] & 0xFF) << 8)
            | (long) (authData[36] & 0xFF);
    int offset = 37 + 16;
    if (authData.length < offset + 2) {
      throw new AuthException(AuthErrorCode.APP_ATTEST_INVALID, "app attest credential length missing", 401);
    }
    int credentialIdLength = ((authData[offset] & 0xFF) << 8) | (authData[offset + 1] & 0xFF);
    offset += 2;
    if (credentialIdLength <= 0 || authData.length < offset + credentialIdLength + 1) {
      throw new AuthException(AuthErrorCode.APP_ATTEST_INVALID, "app attest credential id invalid", 401);
    }
    byte[] credentialId = Arrays.copyOfRange(authData, offset, offset + credentialIdLength);
    offset += credentialIdLength;
    byte[] cosePublicKeyBytes = Arrays.copyOfRange(authData, offset, authData.length);
    return new ParsedAuthData(applicationIdentifierHash, signCount, credentialId, cosePublicKeyBytes);
  }

  private static ParsedAssertionAuthData parseAssertionAuthData(byte[] authData) {
    if (authData == null || authData.length < 37) {
      throw new AuthException(AuthErrorCode.APP_ATTEST_INVALID, "app attest assertion authenticator data invalid", 401);
    }
    byte[] applicationIdentifierHash = Arrays.copyOfRange(authData, 0, 32);
    long signCount =
        ((long) (authData[33] & 0xFF) << 24)
            | ((long) (authData[34] & 0xFF) << 16)
            | ((long) (authData[35] & 0xFF) << 8)
            | (long) (authData[36] & 0xFF);
    return new ParsedAssertionAuthData(applicationIdentifierHash, signCount);
  }

  private static byte[] cosePublicKeySpki(byte[] coseKeyBytes) throws Exception {
    Map<Object, Object> coseKey = parseCborMap(coseKeyBytes);
    int keyType = intValue(coseKey, 1);
    int algorithm = intValue(coseKey, 3);
    int curve = intValue(coseKey, -1);
    byte[] x = bytesValue(coseKey, -2);
    byte[] y = bytesValue(coseKey, -3);
    if (keyType != 2 || algorithm != -7 || curve != 1 || x.length != 32 || y.length != 32) {
      throw new AuthException(AuthErrorCode.APP_ATTEST_INVALID, "app attest public key invalid", 401);
    }

    AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
    parameters.init(new ECGenParameterSpec("secp256r1"));
    ECParameterSpec ecParameterSpec = parameters.getParameterSpec(ECParameterSpec.class);
    ECPoint point = new ECPoint(new BigInteger(1, x), new BigInteger(1, y));
    ECPublicKeySpec publicKeySpec = new ECPublicKeySpec(point, ecParameterSpec);
    PublicKey publicKey = KeyFactory.getInstance("EC").generatePublic(publicKeySpec);
    return publicKey.getEncoded();
  }

  private static String matchApplicationIdentifier(
      byte[] applicationIdentifierHash, List<String> allowedApplicationIdentifiers) {
    for (String allowedApplicationIdentifier : allowedApplicationIdentifiers) {
      byte[] expected = sha256(allowedApplicationIdentifier.getBytes(StandardCharsets.UTF_8));
      if (Arrays.equals(applicationIdentifierHash, expected)) {
        return allowedApplicationIdentifier;
      }
    }
    throw new AuthException(AuthErrorCode.APP_ATTEST_INVALID, "app attest application identifier invalid", 401);
  }

  private static void verifyAssertionSignature(String publicKeySpkiBase64Url, byte[] payload, byte[] signature) {
    try {
      Signature verifier = Signature.getInstance("SHA256withECDSA");
      verifier.initVerify(publicKeyFromSpkiBase64Url(publicKeySpkiBase64Url));
      verifier.update(payload);
      if (!verifier.verify(signature)) {
        throw new AuthException(AuthErrorCode.APP_ATTEST_INVALID, "app attest assertion signature invalid", 401);
      }
    } catch (AuthException e) {
      throw e;
    } catch (Exception e) {
      throw new AuthException(AuthErrorCode.APP_ATTEST_INVALID, "app attest assertion signature verification failed", 401);
    }
  }

  private static PublicKey publicKeyFromSpkiBase64Url(String publicKeySpkiBase64Url) throws Exception {
    byte[] encoded = decodeBase64Url(publicKeySpkiBase64Url);
    return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(encoded));
  }

  private static byte[] extractNonce(X509Certificate certificate) throws Exception {
    byte[] extensionValue = certificate.getExtensionValue(NONCE_EXTENSION_OID);
    if (extensionValue == null || extensionValue.length == 0) {
      throw new AuthException(AuthErrorCode.APP_ATTEST_INVALID, "app attest nonce extension missing", 401);
    }
    DerValue outer = parseDerValue(extensionValue, 0);
    if (outer.tag() != 0x04) {
      throw new AuthException(AuthErrorCode.APP_ATTEST_INVALID, "app attest nonce extension malformed", 401);
    }
    DerValue sequence = parseDerValue(outer.value(), 0);
    if (sequence.tag() != 0x30) {
      throw new AuthException(AuthErrorCode.APP_ATTEST_INVALID, "app attest nonce sequence malformed", 401);
    }

    int offset = 0;
    while (offset < sequence.value().length) {
      DerValue element = parseDerValue(sequence.value(), offset);
      if (element.tag() == 0xA1) {
        DerValue octetString = parseDerValue(element.value(), 0);
        if (octetString.tag() != 0x04) {
          throw new AuthException(AuthErrorCode.APP_ATTEST_INVALID, "app attest nonce payload malformed", 401);
        }
        return octetString.value();
      }
      offset += element.totalLength();
    }
    throw new AuthException(AuthErrorCode.APP_ATTEST_INVALID, "app attest nonce payload missing", 401);
  }

  private static DerValue parseDerValue(byte[] data, int offset) {
    if (data == null || offset < 0 || offset + 2 > data.length) {
      throw new AuthException(AuthErrorCode.APP_ATTEST_INVALID, "app attest der value truncated", 401);
    }
    int tag = data[offset] & 0xFF;
    int lengthByte = data[offset + 1] & 0xFF;
    int headerLength = 2;
    int length;
    if ((lengthByte & 0x80) == 0) {
      length = lengthByte;
    } else {
      int lengthBytes = lengthByte & 0x7F;
      if (lengthBytes <= 0 || lengthBytes > 4 || offset + 2 + lengthBytes > data.length) {
        throw new AuthException(AuthErrorCode.APP_ATTEST_INVALID, "app attest der length invalid", 401);
      }
      length = 0;
      for (int index = 0; index < lengthBytes; index += 1) {
        length = (length << 8) | (data[offset + 2 + index] & 0xFF);
      }
      headerLength += lengthBytes;
    }
    if (length < 0 || offset + headerLength + length > data.length) {
      throw new AuthException(AuthErrorCode.APP_ATTEST_INVALID, "app attest der payload truncated", 401);
    }
    return new DerValue(
        tag,
        Arrays.copyOfRange(data, offset + headerLength, offset + headerLength + length),
        headerLength + length);
  }

  private static Map<Object, Object> parseCborMap(byte[] payload) throws Exception {
    Map<Object, Object> value = CBOR_MAPPER.readValue(payload, CBOR_MAP_TYPE);
    if (value == null || value.isEmpty()) {
      throw new AuthException(AuthErrorCode.APP_ATTEST_INVALID, "app attest cbor payload empty", 401);
    }
    return value;
  }

  @SuppressWarnings("unchecked")
  private static Map<Object, Object> mapValue(Map<Object, Object> payload, Object key) {
    Object value = lookupValue(payload, key);
    if (value instanceof Map<?, ?> map) {
      return (Map<Object, Object>) map;
    }
    throw new AuthException(AuthErrorCode.APP_ATTEST_INVALID, "app attest cbor map missing", 401);
  }

  private static String stringValue(Map<Object, Object> payload, Object key) {
    Object value = lookupValue(payload, key);
    if (value instanceof String stringValue && StringUtils.hasText(stringValue)) {
      return stringValue.trim();
    }
    throw new AuthException(AuthErrorCode.APP_ATTEST_INVALID, "app attest string field missing", 401);
  }

  private static byte[] bytesValue(Map<Object, Object> payload, Object key) {
    Object value = lookupValue(payload, key);
    if (value instanceof byte[] bytes && bytes.length > 0) {
      return bytes;
    }
    throw new AuthException(AuthErrorCode.APP_ATTEST_INVALID, "app attest bytes field missing", 401);
  }

  private static byte[] optionalBytes(Map<Object, Object> payload, Object key) {
    Object value = lookupValue(payload, key);
    if (value instanceof byte[] bytes && bytes.length > 0) {
      return bytes;
    }
    return null;
  }

  private static int intValue(Map<Object, Object> payload, int key) {
    for (Map.Entry<Object, Object> entry : payload.entrySet()) {
      if (!matchesKey(entry.getKey(), key)) {
        continue;
      }
      if (entry.getValue() instanceof Number number) {
        return number.intValue();
      }
      break;
    }
    throw new AuthException(AuthErrorCode.APP_ATTEST_INVALID, "app attest integer field missing", 401);
  }

  private static List<byte[]> bytesListValue(Map<Object, Object> payload, Object key) {
    Object value = lookupValue(payload, key);
    if (!(value instanceof List<?> listValue) || listValue.isEmpty()) {
      throw new AuthException(AuthErrorCode.APP_ATTEST_INVALID, "app attest certificate list missing", 401);
    }
    List<byte[]> out = new ArrayList<>(listValue.size());
    for (Object item : listValue) {
      if (!(item instanceof byte[] bytes) || bytes.length == 0) {
        throw new AuthException(AuthErrorCode.APP_ATTEST_INVALID, "app attest certificate entry invalid", 401);
      }
      out.add(bytes);
    }
    return out;
  }

  private static boolean matchesKey(Object actual, int expected) {
    if (actual instanceof Number number) {
      return number.intValue() == expected;
    }
    if (actual instanceof String stringValue) {
      try {
        return Integer.parseInt(stringValue.trim()) == expected;
      } catch (NumberFormatException ignored) {
        return false;
      }
    }
    return false;
  }

  private static Object lookupValue(Map<Object, Object> payload, Object expectedKey) {
    if (!(expectedKey instanceof Number)) {
      return payload.get(expectedKey);
    }
    int expected = ((Number) expectedKey).intValue();
    for (Map.Entry<Object, Object> entry : payload.entrySet()) {
      if (matchesKey(entry.getKey(), expected)) {
        return entry.getValue();
      }
    }
    return null;
  }

  private X509Certificate loadRootCertificate() {
    try (InputStream in = new ClassPathResource(APPLE_APP_ATTEST_ROOT_CA_RESOURCE).getInputStream()) {
      return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
    } catch (Exception e) {
      throw new IllegalStateException("failed to load Apple App Attestation root certificate", e);
    }
  }

  private List<String> requireAllowedApplicationIdentifiers() {
    List<String> allowed = authProperties.getIntegrity().iosAllowedApplicationIdentifiersList();
    if (allowed.isEmpty()) {
      throw new AuthException(
          AuthErrorCode.PROVIDER_UNAVAILABLE,
          "ios allowed application identifiers not configured for app attest",
          503);
    }
    return allowed;
  }

  private AppAttestRegistrationRecord requireRegistration(String normalizedDeviceId) {
    AppAttestRegistrationRecord registration = store.getAppAttestRegistration(normalizedDeviceId).orElse(null);
    if (registration == null) {
      throw new AuthException(AuthErrorCode.APP_ATTEST_REQUIRED, "app attest registration required", 401);
    }
    if (!requireAllowedApplicationIdentifiers().contains(registration.applicationIdentifier())) {
      throw new AuthException(AuthErrorCode.APP_ATTEST_INVALID, "app attest application identifier no longer allowed", 401);
    }
    return registration;
  }

  private boolean requiresAppAttest(String platform) {
    if (!authProperties.getIntegrity().isIosAppAttestEnabled()) {
      return false;
    }
    return "ios".equals(normalizePlatform(platform));
  }

  private static String normalizePlatform(String platform) {
    String normalized = emptyToNull(platform);
    return normalized == null ? "unknown" : normalized.toLowerCase(Locale.ROOT);
  }

  private static String requireDeviceId(String deviceId) {
    String normalized = emptyToNull(deviceId);
    if (normalized == null) {
      throw new AuthException(AuthErrorCode.UNAUTHORIZED, "missing device id", 401);
    }
    return normalized;
  }

  private static String requireNonEmpty(String value, String label) {
    String normalized = emptyToNull(value);
    if (normalized == null) {
      throw new AuthException(AuthErrorCode.BAD_REQUEST, label + " missing", 400);
    }
    return normalized;
  }

  private static String requireProtectedHeader(String value, String label) {
    String normalized = emptyToNull(value);
    if (normalized == null) {
      throw new AuthException(AuthErrorCode.APP_ATTEST_REQUIRED, label + " missing", 401);
    }
    return normalized;
  }

  private static String emptyToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String normalizeMethod(String method) {
    String normalized = emptyToNull(method);
    if (normalized == null) {
      throw new AuthException(AuthErrorCode.BAD_REQUEST, "app attest method missing", 400);
    }
    return normalized.toUpperCase(Locale.ROOT);
  }

  private static String normalizePath(String path) {
    String normalized = emptyToNull(path);
    if (normalized == null || !normalized.startsWith("/")) {
      throw new AuthException(AuthErrorCode.BAD_REQUEST, "app attest path invalid", 400);
    }
    int queryIndex = normalized.indexOf('?');
    String out = queryIndex >= 0 ? normalized.substring(0, queryIndex) : normalized;
    if (out.length() > 1 && out.endsWith("/")) {
      out = out.substring(0, out.length() - 1);
    }
    return out;
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
      throw new AuthException(AuthErrorCode.APP_ATTEST_INVALID, "app attest base64 value invalid", 401);
    }
  }

  private static byte[] sha256(byte[] input) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(input);
    } catch (Exception e) {
      throw new IllegalStateException("sha256 error", e);
    }
  }

  private static byte[] concat(byte[] left, byte[] right) {
    byte[] out = new byte[left.length + right.length];
    System.arraycopy(left, 0, out, 0, left.length);
    System.arraycopy(right, 0, out, left.length, right.length);
    return out;
  }

  private static String base64UrlEncode(byte[] value) {
    return B64URL_ENCODER.encodeToString(value);
  }

  private static long now() {
    return System.currentTimeMillis();
  }

  private record ParsedAuthData(
      byte[] applicationIdentifierHash, long signCount, byte[] credentialId, byte[] cosePublicKeyBytes) {}

  private record ParsedAssertionAuthData(byte[] applicationIdentifierHash, long signCount) {}

  private record VerifiedAttestation(
      String applicationIdentifier,
      String publicKeySpkiBase64Url,
      String credentialIdBase64Url,
      String receiptBase64Url) {}

  private record VerifiedAssertion(String applicationIdentifier, long signCount) {}

  private record DerValue(int tag, byte[] value, int totalLength) {}
}
