package io.statusmvp.pricebackend.auth;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AuthConfigurationGuard {
  private static final Logger log = LoggerFactory.getLogger(AuthConfigurationGuard.class);
  private static final String WEB3AUTH_APP_REDIRECT_URI = "veilwalletw3a://openlogin";

  private final AuthProperties authProperties;

  public AuthConfigurationGuard(AuthProperties authProperties) {
    this.authProperties = authProperties;
  }

  @PostConstruct
  public void validate() {
    if (!authProperties.isEnabled()) {
      log.info("[AuthConfigurationGuard] auth disabled, skip auth startup validation");
      return;
    }
    if (!authProperties.isSocialEnabled()) {
      log.info("[AuthConfigurationGuard] social auth disabled, skip protected social startup validation");
      return;
    }

    String publicBaseUrl = normalizeAbsoluteUrl(authProperties.getPublicBaseUrl(), "AUTH_PUBLIC_BASE_URL");
    List<String> appRedirectAllowlist = authProperties.appRedirectAllowPrefixes();
    if (appRedirectAllowlist.isEmpty()) {
      throw new IllegalStateException(
          "AUTH_APP_REDIRECT_ALLOWLIST must not be empty when AUTH_SOCIAL_ENABLED=true");
    }

    requireAllowedRedirect(publicBaseUrl + "/openlogin", appRedirectAllowlist, "AUTH_APP_REDIRECT_ALLOWLIST");
    requireAllowedRedirect(WEB3AUTH_APP_REDIRECT_URI, appRedirectAllowlist, "AUTH_APP_REDIRECT_ALLOWLIST");
    requireText(
        authProperties.getWeb3auth().getPrivateKeyPem(),
        "AUTH_WEB3AUTH_PRIVATE_KEY_PEM is required when AUTH_SOCIAL_ENABLED=true");

    if (authProperties.isXEnabled()) {
      AuthProperties.X x = authProperties.getX();
      requireText(x.getClientId(), "AUTH_X_CLIENT_ID is required when X social auth is enabled");
      requireText(x.getClientSecret(), "AUTH_X_CLIENT_SECRET is required when X social auth is enabled");
      requireText(x.getStateSecret(), "AUTH_X_STATE_SECRET is required when X social auth is enabled");
      requireExactAbsoluteUrl(
          x.getRedirectUri(),
          publicBaseUrl + "/api/v1/auth/x/callback",
          "AUTH_X_REDIRECT_URI");
    }

    if (authProperties.isTgEnabled()) {
      AuthProperties.Tg tg = authProperties.getTg();
      requireText(tg.getBotUsername(), "AUTH_TG_BOT_USERNAME is required when Telegram login is enabled");
      requireText(tg.getBotToken(), "AUTH_TG_BOT_TOKEN is required when Telegram login is enabled");
      requireText(tg.getStateSecret(), "AUTH_TG_STATE_SECRET is required when Telegram login is enabled");
      requireExactAbsoluteUrl(
          tg.getRedirectUri(),
          publicBaseUrl + "/api/v1/auth/tg/login",
          "AUTH_TG_REDIRECT_URI");
    }

    if (authProperties.getIntegrity().isAndroidPlayIntegrityEnabled()
        && authProperties.getIntegrity().androidProtectedAuthAllowedChannelsList().contains("play")) {
      requireText(
          authProperties.getIntegrity().getAndroidPlayIntegrityPackageName(),
          "AUTH_ANDROID_PLAY_INTEGRITY_PACKAGE_NAME is required when Play Integrity protected auth is enabled");
      requireText(
          authProperties.getIntegrity().getAndroidPlayIntegrityServiceAccountEmail(),
          "AUTH_ANDROID_PLAY_INTEGRITY_SERVICE_ACCOUNT_EMAIL is required when Play Integrity protected auth is enabled");
      requireText(
          authProperties.getIntegrity().getAndroidPlayIntegrityServiceAccountPrivateKeyPem(),
          "AUTH_ANDROID_PLAY_INTEGRITY_SERVICE_ACCOUNT_PRIVATE_KEY_PEM is required when Play Integrity protected auth is enabled");
    }

    if (authProperties.getIntegrity().isIosAppAttestEnabled()
        && authProperties.getIntegrity().iosAllowedApplicationIdentifiersList().isEmpty()) {
      throw new IllegalStateException(
          "AUTH_IOS_ALLOWED_APPLICATION_IDENTIFIERS must not be empty when iOS App Attest is enabled");
    }

    log.info(
        "[AuthConfigurationGuard] social auth startup validation passed publicBaseUrl={} allowlistSize={}",
        publicBaseUrl,
        appRedirectAllowlist.size());
  }

  private static void requireAllowedRedirect(
      String candidateRedirectUri, List<String> allowlist, String envName) {
    if (!AuthUtils.isAllowedRedirect(candidateRedirectUri, allowlist)) {
      throw new IllegalStateException(
          envName + " must allow redirect uri: " + candidateRedirectUri);
    }
  }

  private static void requireExactAbsoluteUrl(String value, String expected, String envName) {
    String normalizedActual = normalizeAbsoluteUrl(value, envName);
    String normalizedExpected = normalizeAbsoluteUrl(expected, envName + " expected");
    if (!normalizedExpected.equals(normalizedActual)) {
      throw new IllegalStateException(
          envName + " must equal " + normalizedExpected + " but was " + normalizedActual);
    }
  }

  private static String normalizeAbsoluteUrl(String value, String envName) {
    String normalized = requireText(value, envName + " must not be empty");
    URI uri;
    try {
      uri = URI.create(normalized);
    } catch (Exception error) {
      throw new IllegalStateException(envName + " must be a valid absolute URL", error);
    }
    if (uri.getScheme() == null || uri.getScheme().isBlank() || uri.getHost() == null || uri.getHost().isBlank()) {
      throw new IllegalStateException(envName + " must be a valid absolute URL");
    }
    String path = uri.getPath() == null ? "" : uri.getPath();
    String normalizedPath = path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;
    StringBuilder out = new StringBuilder();
    out.append(uri.getScheme()).append("://").append(uri.getHost());
    if (uri.getPort() >= 0) {
      out.append(':').append(uri.getPort());
    }
    out.append(normalizedPath);
    return out.toString();
  }

  private static String requireText(String value, String message) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty()) {
      throw new IllegalStateException(message);
    }
    return normalized;
  }
}
