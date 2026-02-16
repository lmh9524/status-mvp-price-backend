package io.statusmvp.pricebackend.auth;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

public final class AuthUtils {
  private static final SecureRandom RANDOM = new SecureRandom();

  private AuthUtils() {}

  public static String randomBase64Url(int bytesLength) {
    byte[] bytes = new byte[Math.max(16, bytesLength)];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public static String randomWalletSub() {
    return "wallet_" + randomBase64Url(18).toLowerCase(Locale.ROOT);
  }

  public static String providerSub(AuthProvider provider, String providerUserId) {
    return provider.code() + ":" + providerUserId;
  }

  public static String sha256Base64Url(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] out = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
    } catch (Exception e) {
      throw new IllegalStateException("sha256 error", e);
    }
  }

  public static boolean isAllowedRedirect(String uri, List<String> prefixes) {
    if (uri == null || uri.isBlank()) return false;
    try {
      URI parsed = URI.create(uri);
      if (parsed.getScheme() == null || parsed.getScheme().isBlank()) return false;
      if (prefixes.isEmpty()) return false;
      String normalized = uri.trim();
      return prefixes.stream().anyMatch(normalized::startsWith);
    } catch (Exception e) {
      return false;
    }
  }

  public static String normalizeIp(String ip) {
    if (ip == null) return "";
    return ip.trim().toLowerCase(Locale.ROOT);
  }
}

