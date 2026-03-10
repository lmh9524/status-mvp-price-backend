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
    if (prefixes == null || prefixes.isEmpty()) return false;

    String normalized = uri.trim();
    if (normalized.isEmpty()) return false;

    try {
      URI parsed = URI.create(normalized);
      if (parsed.getScheme() == null || parsed.getScheme().isBlank()) return false;
    } catch (Exception e) {
      return false;
    }

    for (String prefix : prefixes) {
      if (prefix == null) continue;
      String p = prefix.trim();
      if (p.isEmpty()) continue;

      if (normalized.equals(p)) return true;
      if (p.endsWith("/") && normalized.startsWith(p)) return true;
      if (!normalized.startsWith(p)) continue;
      if (normalized.length() == p.length()) return true;

      char next = normalized.charAt(p.length());
      // Prevent prefix confusion like `https://example.com.evil` when allowlist has `https://example.com`.
      // Allow common URL continuations for paths / queries / fragments.
      if (next == '/' || next == '?' || next == '#') return true;
    }

    return false;
  }

  public static String normalizeIp(String ip) {
    if (ip == null) return "";
    return ip.trim().toLowerCase(Locale.ROOT);
  }

  public static String firstForwardedValue(String value) {
    if (value == null) return "";
    String trimmed = value.trim();
    if (trimmed.isEmpty()) return "";
    int idx = trimmed.indexOf(',');
    if (idx >= 0) {
      trimmed = trimmed.substring(0, idx).trim();
    }
    return trimmed;
  }

  public static String normalizeForwardedHost(String value) {
    if (value == null) return "";
    String host = firstForwardedValue(value);
    return host == null ? "" : host.trim();
  }

  public static String normalizeScheme(String value) {
    if (value == null) return "";
    return value.trim().toLowerCase(Locale.ROOT);
  }

  public static boolean isTrustedProxy(String remoteIp, List<String> trustedProxyIps) {
    String normalizedRemoteIp = normalizeIp(remoteIp);
    if (normalizedRemoteIp.isEmpty()) return false;
    for (String trusted : trustedProxyIps == null ? List.<String>of() : trustedProxyIps) {
      if (normalizedRemoteIp.equals(normalizeIp(trusted))) {
        return true;
      }
    }
    return false;
  }

  public static String normalizeBaseUrl(String value) {
    if (value == null) return "";
    String trimmed = value.trim();
    if (trimmed.isEmpty()) return "";
    try {
      URI parsed = URI.create(trimmed);
      String scheme = normalizeScheme(parsed.getScheme());
      String host = parsed.getHost();
      if (scheme.isEmpty() || host == null || host.isBlank()) {
        return "";
      }
      StringBuilder out = new StringBuilder();
      out.append(scheme).append("://").append(host.trim());
      if (parsed.getPort() > 0) {
        out.append(':').append(parsed.getPort());
      }
      return out.toString();
    } catch (Exception e) {
      return "";
    }
  }

  public static String buildBaseUrl(String scheme, String host) {
    String normalizedScheme = normalizeScheme(scheme);
    String normalizedHost = normalizeForwardedHost(host);
    if (normalizedScheme.isEmpty() || normalizedHost.isEmpty()) {
      return "";
    }
    return normalizedScheme + "://" + normalizedHost;
  }
}
