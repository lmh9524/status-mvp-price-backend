package io.statusmvp.pricebackend.auth;

import io.statusmvp.pricebackend.auth.dto.AuthDtos.TelegramLoginRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

@Service
public class TelegramVerifier {
  private final AuthProperties authProperties;

  public TelegramVerifier(AuthProperties authProperties) {
    this.authProperties = authProperties;
  }

  public String verifyAndGetUserId(TelegramLoginRequest request) {
    String botToken = authProperties.getTg().getBotToken();
    if (botToken == null || botToken.isBlank()) {
      throw new AuthException(AuthErrorCode.PROVIDER_UNAVAILABLE, "telegram bot token not configured", 503);
    }
    long authDate;
    try {
      authDate = Long.parseLong(request.authDate());
    } catch (NumberFormatException e) {
      throw new AuthException(AuthErrorCode.TELEGRAM_VERIFY_FAILED, "invalid telegram auth_date", 400);
    }
    long now = Instant.now().getEpochSecond();
    long maxAge = Math.max(1, authProperties.getTg().getAuthMaxAgeSeconds());
    if (Math.abs(now - authDate) > maxAge) {
      throw new AuthException(AuthErrorCode.TELEGRAM_VERIFY_FAILED, "telegram login expired", 401);
    }

    String checkString = buildDataCheckString(request);
    String expected = hmacHexSha256(checkString, sha256Bytes(botToken));
    if (!secureEquals(expected, request.hash())) {
      throw new AuthException(AuthErrorCode.TELEGRAM_VERIFY_FAILED, "telegram hash invalid", 401);
    }
    return request.id().trim();
  }

  private static String buildDataCheckString(TelegramLoginRequest request) {
    List<String> pairs = new ArrayList<>();
    pairs.add("auth_date=" + request.authDate());
    pairs.add("id=" + request.id());
    if (request.firstName() != null && !request.firstName().isBlank()) {
      pairs.add("first_name=" + request.firstName());
    }
    if (request.lastName() != null && !request.lastName().isBlank()) {
      pairs.add("last_name=" + request.lastName());
    }
    if (request.username() != null && !request.username().isBlank()) {
      pairs.add("username=" + request.username());
    }
    if (request.photoUrl() != null && !request.photoUrl().isBlank()) {
      pairs.add("photo_url=" + request.photoUrl());
    }
    return pairs.stream().sorted(Comparator.naturalOrder()).reduce((a, b) -> a + "\n" + b).orElse("");
  }

  private static byte[] sha256Bytes(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return digest.digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String hmacHexSha256(String value, byte[] secret) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      byte[] out = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (byte b : out) sb.append(String.format("%02x", b));
      return sb.toString();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static boolean secureEquals(String left, String right) {
    if (left == null || right == null) return false;
    byte[] a = left.getBytes(StandardCharsets.UTF_8);
    byte[] b = right.getBytes(StandardCharsets.UTF_8);
    if (a.length != b.length) return false;
    int diff = 0;
    for (int i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
    return diff == 0;
  }
}

