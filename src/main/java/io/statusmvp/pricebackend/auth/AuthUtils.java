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

  public static boolean isAllowedRedirect(String uri, List<String> allowlist) {
    if (uri == null || uri.isBlank()) return false;
    if (allowlist == null || allowlist.isEmpty()) return false;

    URI candidate;
    try {
      candidate = URI.create(uri.trim());
    } catch (Exception e) {
      return false;
    }

    String candidateScheme = normalizeScheme(candidate.getScheme());
    String candidateHost = normalizeRedirectHost(candidate);
    int candidatePort = normalizeRedirectPort(candidate);
    String candidateNormalized = normalizeRedirectUri(candidate);
    if (candidateScheme.isEmpty() || candidateHost.isEmpty() || candidateNormalized.isEmpty()) return false;

    for (String allowedValue : allowlist) {
      if (allowedValue == null) continue;
      String current = allowedValue.trim();
      if (current.isEmpty()) continue;

      URI allowed;
      try {
        allowed = URI.create(current);
      } catch (Exception e) {
        continue;
      }

      String allowedNormalized = normalizeRedirectUri(allowed);
      if (allowedNormalized.isEmpty()) continue;
      if (!candidateScheme.equals(normalizeScheme(allowed.getScheme()))) continue;
      if (!candidateHost.equals(normalizeRedirectHost(allowed))) continue;
      if (candidatePort != normalizeRedirectPort(allowed)) continue;
      if (!candidateNormalized.equals(allowedNormalized)) continue;
      return true;
    }

    return false;
  }

  private static String normalizeRedirectHost(URI uri) {
    if (uri == null) return "";
    String host = uri.getHost();
    if (host == null || host.isBlank()) return "";
    return host.trim().toLowerCase(Locale.ROOT);
  }

  private static int normalizeRedirectPort(URI uri) {
    if (uri == null) return -1;
    return uri.getPort();
  }

  private static String normalizeRedirectPath(String value) {
    String raw = value == null ? "" : value.trim();
    if (raw.isEmpty() || "/".equals(raw)) return "/";
    return raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
  }

  private static String normalizeRedirectUri(URI uri) {
    if (uri == null) return "";
    String scheme = normalizeScheme(uri.getScheme());
    String host = normalizeRedirectHost(uri);
    if (scheme.isEmpty() || host.isEmpty()) return "";
    String path = normalizeRedirectPath(uri.getPath());
    StringBuilder out = new StringBuilder();
    out.append(scheme).append("://").append(host);
    if (uri.getPort() >= 0) {
      out.append(':').append(uri.getPort());
    }
    if (!path.isEmpty()) {
      out.append(path);
    }
    String query = uri.getQuery();
    if (query != null && !query.isBlank()) {
      out.append('?').append(query.trim());
    }
    String fragment = uri.getFragment();
    if (fragment != null && !fragment.isBlank()) {
      out.append('#').append(fragment.trim());
    }
    return out.toString();
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
