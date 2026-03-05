package io.statusmvp.pricebackend.auth;

import io.statusmvp.pricebackend.auth.dto.AuthDtos;
import io.statusmvp.pricebackend.auth.model.AuthCodeRecord;
import io.statusmvp.pricebackend.auth.model.FavoriteItem;
import io.statusmvp.pricebackend.auth.model.HistoryItem;
import io.statusmvp.pricebackend.auth.model.OAuthStateRecord;
import io.statusmvp.pricebackend.auth.model.ProviderBinding;
import io.statusmvp.pricebackend.auth.model.RefreshTokenRecord;
import io.statusmvp.pricebackend.auth.model.SiweNonceRecord;
import io.statusmvp.pricebackend.auth.model.SyncFavorites;
import io.statusmvp.pricebackend.auth.model.SyncHistory;
import io.statusmvp.pricebackend.auth.model.WalletProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class AuthService {
  private static final Logger log = LoggerFactory.getLogger(AuthService.class);
  private static final int MAX_FAVORITES = 500;
  private static final int MAX_HISTORY = 1000;

  private final AuthProperties authProperties;
  private final AuthRedisStore store;
  private final XOAuthClient xOAuthClient;
  private final TelegramVerifier telegramVerifier;
  private final AuthRiskService riskService;
  private final AuthJwtService jwtService;
  private final AuthMetrics metrics;
  private final ObjectMapper objectMapper;

  public AuthService(
      AuthProperties authProperties,
      AuthRedisStore store,
      XOAuthClient xOAuthClient,
      TelegramVerifier telegramVerifier,
      AuthRiskService riskService,
      AuthJwtService jwtService,
      AuthMetrics metrics,
      ObjectMapper objectMapper) {
    this.authProperties = authProperties;
    this.store = store;
    this.xOAuthClient = xOAuthClient;
    this.telegramVerifier = telegramVerifier;
    this.riskService = riskService;
    this.jwtService = jwtService;
    this.metrics = metrics;
    this.objectMapper = objectMapper;
  }

  public AuthDtos.XStartResponse startXLogin(String appRedirectUri, String deviceId) {
    ensureAuthEnabled();
    ensureXEnabled();
    validateXConfig();
    validateAppRedirect(appRedirectUri);

    String state;
    String codeVerifier;
    String codeChallenge;

    String stateSecret = authProperties.getX().getStateSecret();
    if (StringUtils.hasText(stateSecret)) {
      XOAuthStateTokens.Issued issued =
          XOAuthStateTokens.issue(
              objectMapper,
              stateSecret,
              emptyToNull(appRedirectUri),
              authProperties.getOauthStateTtlSeconds(),
              now());
      state = issued.state();
      codeVerifier = issued.codeVerifier();
      codeChallenge = AuthUtils.sha256Base64Url(codeVerifier);
    } else {
      state = AuthUtils.randomBase64Url(24);
      codeVerifier = AuthUtils.randomBase64Url(48);
      codeChallenge = AuthUtils.sha256Base64Url(codeVerifier);
      long now = now();
      OAuthStateRecord stateRecord =
          new OAuthStateRecord(
              state,
              AuthProvider.X.code(),
              codeVerifier,
              emptyToNull(appRedirectUri),
              now,
              now + authProperties.getOauthStateTtlSeconds() * 1000);
      store.putOAuthState(stateRecord, authProperties.getOauthStateTtlSeconds());
    }

    store.putOAuthStateDevice(state, deviceId, authProperties.getOauthStateTtlSeconds());
    return new AuthDtos.XStartResponse(
        xOAuthClient.buildAuthorizeUrl(state, codeChallenge), state,
        authProperties.getOauthStateTtlSeconds());
  }

  public void validateAppRedirectUri(String appRedirectUri) {
    validateAppRedirect(appRedirectUri);
  }

  public void validateTgWidgetRequest(String appRedirectUri) {
    ensureAuthEnabled();
    ensureTgEnabled();
    validateAppRedirect(appRedirectUri);
  }

  public String telegramBotUsername() {
    return emptyToNull(authProperties.getTg().getBotUsername());
  }

  public XCallbackResult handleXCallback(
      String code,
      String state,
      String error,
      String errorDescription,
      String ip,
      String deviceId) {
    ensureAuthEnabled();
    ensureXEnabled();
    validateXConfig();
    riskService.checkIpAllowed(ip);
    riskService.checkLoginRateLimits(ip, deviceId);

    String appRedirectUri;
    String codeVerifier;
    String effectiveDeviceId = emptyToNull(deviceId);

    String stateSecret = authProperties.getX().getStateSecret();
    if (StringUtils.hasText(stateSecret) && XOAuthStateTokens.looksLikeToken(state)) {
      XOAuthStateTokens.Parsed parsed =
          XOAuthStateTokens.parseAndVerify(objectMapper, stateSecret, state, now());
      if (parsed == null) {
        throw new AuthException(AuthErrorCode.OAUTH_STATE_INVALID, "oauth state invalid", 401);
      }
      appRedirectUri = parsed.appRedirectUri();
      codeVerifier = parsed.codeVerifier();
      if (parsed.expiresAtMs() < now()) {
        throw new AuthException(AuthErrorCode.OAUTH_STATE_EXPIRED, "oauth state expired", 401);
      }
      validateAppRedirect(appRedirectUri);
      if (!StringUtils.hasText(effectiveDeviceId)) {
        effectiveDeviceId = store.consumeOAuthStateDevice(state).orElse(null);
      }
    } else {
      OAuthStateRecord stateRecord =
          store
              .consumeOAuthState(state)
              .orElseThrow(
                  () ->
                      new AuthException(
                          AuthErrorCode.OAUTH_STATE_INVALID, "oauth state invalid", 401));
      if (stateRecord.expiresAt() < now()) {
        throw new AuthException(AuthErrorCode.OAUTH_STATE_EXPIRED, "oauth state expired", 401);
      }
      appRedirectUri = stateRecord.appRedirectUri();
      codeVerifier = stateRecord.codeVerifier();
      if (!StringUtils.hasText(effectiveDeviceId)) {
        effectiveDeviceId = store.consumeOAuthStateDevice(state).orElse(null);
      }
    }
    if (!StringUtils.hasText(deviceId) && StringUtils.hasText(effectiveDeviceId)) {
      riskService.checkLoginDeviceRateLimit(effectiveDeviceId);
    }
    if (StringUtils.hasText(error)) {
      metrics.loginFailure("x", "oauth_error");
      throw new AuthException(
          AuthErrorCode.OAUTH_EXCHANGE_FAILED,
          "x oauth failed: " + (errorDescription == null ? error : errorDescription),
          401);
    }
    if (!StringUtils.hasText(code)) {
      metrics.loginFailure("x", "missing_code");
      throw new AuthException(AuthErrorCode.OAUTH_EXCHANGE_FAILED, "x oauth code missing", 400);
    }

    XOAuthClient.TokenExchangeResult tokens = xOAuthClient.exchangeCodeForTokens(code, codeVerifier);
    String accessToken = tokens.accessToken();

    String providerUserId = xOAuthClient.tryExtractUserIdFromIdToken(tokens.idToken());
    if (!StringUtils.hasText(providerUserId)) {
      try {
        providerUserId = xOAuthClient.fetchUserId(accessToken);
      } catch (AuthException e) {
        if (e.getCode() == AuthErrorCode.PROVIDER_UNAVAILABLE) {
          // IMPORTANT: We must not mint an authCode with an unstable "provider user id" (e.g. refresh_token hash).
          // Otherwise the same X account can map to different Web3Auth verifierIds across logins, producing different wallets.
          //
          // If X userinfo is temporarily unavailable, return a resumable error so the app can retry.
          if (StringUtils.hasText(stateSecret) && StringUtils.hasText(effectiveDeviceId)) {
            try {
              String resumeToken =
                  XOAuthResumeTokens.issue(objectMapper, stateSecret, effectiveDeviceId, accessToken, 600, now());
              throw new AuthException(
                  AuthErrorCode.PROVIDER_UNAVAILABLE,
                  e.getMessage(),
                  503,
                  e.getRetryAfterSeconds() == null ? 3 : e.getRetryAfterSeconds(),
                  Map.of(
                      "resumeToken", resumeToken,
                      "providerStatus", e.getDetails().getOrDefault("providerStatus", 0)));
            } catch (Exception ignored) {
              // fallthrough to original exception
            }
          }
        }
        if (!StringUtils.hasText(providerUserId)) throw e;
      }
    }
    String providerSub = AuthUtils.providerSub(AuthProvider.X, providerUserId);
    riskService.checkProviderAllowed(providerSub);

    AuthCodeRecord authCode = issueAuthCode(AuthProvider.X, providerUserId, providerSub);
    metrics.loginSuccess("x");
    return new XCallbackResult(
        new AuthDtos.AuthCodeResponse(
            AuthProvider.X.code(),
            providerUserId,
            providerSub,
            authCode.code(),
            authProperties.getOneTimeCodeTtlSeconds()),
        appRedirectUri,
        state);
  }

  public AuthDtos.AuthCodeResponse resumeXLogin(String resumeToken, String ip, String deviceId) {
    ensureAuthEnabled();
    ensureXEnabled();
    validateXConfig();
    riskService.checkIpAllowed(ip);
    riskService.checkLoginRateLimits(ip, deviceId);

    if (!StringUtils.hasText(deviceId)) {
      throw new AuthException(AuthErrorCode.UNAUTHORIZED, "missing device id", 401);
    }

    String stateSecret = authProperties.getX().getStateSecret();
    if (!StringUtils.hasText(stateSecret)) {
      throw new AuthException(AuthErrorCode.PROVIDER_UNAVAILABLE, "x oauth not configured", 503);
    }

    XOAuthResumeTokens.Parsed parsed =
        XOAuthResumeTokens.parseAndDecrypt(objectMapper, stateSecret, resumeToken, now());
    if (parsed == null) {
      throw new AuthException(AuthErrorCode.OAUTH_STATE_INVALID, "oauth resume token invalid", 401);
    }
    if (parsed.deviceId() != null && !deviceId.trim().equals(parsed.deviceId().trim())) {
      throw new AuthException(AuthErrorCode.UNAUTHORIZED, "invalid device id", 401);
    }

    // X userinfo may intermittently 503 from some regions. In resume flow (app->backend), we can afford
    // a few short retries to improve UX without risking browser/proxy callback timeouts.
    String providerUserId = null;
    AuthException lastUnavailable = null;
    long backoffMs = 350L;
    final int maxAttempts = 5;
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        providerUserId = xOAuthClient.fetchUserId(parsed.accessToken());
        break;
      } catch (AuthException e) {
        if (e.getCode() != AuthErrorCode.PROVIDER_UNAVAILABLE) {
          throw e;
        }
        lastUnavailable = e;
        if (attempt >= maxAttempts) break;
        try {
          Thread.sleep(backoffMs);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          break;
        }
        backoffMs = Math.min(2_000L, backoffMs * 2);
      }
    }
    if (!StringUtils.hasText(providerUserId)) {
      if (lastUnavailable != null) throw lastUnavailable;
      throw new AuthException(AuthErrorCode.PROVIDER_UNAVAILABLE, "x provider unavailable", 503, 3, Map.of());
    }
    String providerSub = AuthUtils.providerSub(AuthProvider.X, providerUserId);
    riskService.checkProviderAllowed(providerSub);

    AuthCodeRecord authCode = issueAuthCode(AuthProvider.X, providerUserId, providerSub);
    metrics.loginSuccess("x");
    return new AuthDtos.AuthCodeResponse(
        AuthProvider.X.code(),
        providerUserId,
        providerSub,
        authCode.code(),
        authProperties.getOneTimeCodeTtlSeconds());
  }

  public AuthDtos.AuthCodeResponse telegramLogin(
      AuthDtos.TelegramLoginRequest request, String ip, String deviceId) {
    ensureAuthEnabled();
    ensureTgEnabled();
    riskService.checkIpAllowed(ip);
    riskService.checkLoginRateLimits(ip, deviceId);
    validateAppRedirect(request.appRedirectUri());

    String providerUserId = telegramVerifier.verifyAndGetUserId(request);
    String providerSub = AuthUtils.providerSub(AuthProvider.TG, providerUserId);
    riskService.checkProviderAllowed(providerSub);

    AuthCodeRecord authCode = issueAuthCode(AuthProvider.TG, providerUserId, providerSub);
    metrics.loginSuccess("tg");
    return new AuthDtos.AuthCodeResponse(
        AuthProvider.TG.code(),
        providerUserId,
        providerSub,
        authCode.code(),
        authProperties.getOneTimeCodeTtlSeconds());
  }

  public AuthDtos.ExchangeResponse exchange(AuthDtos.ExchangeRequest request) {
    ensureAuthEnabled();
    AuthCodeRecord codeRecord = consumeValidAuthCode(request.code());
    String providerSub = codeRecord.providerSub();
    String walletSub = resolveOrCreateWalletForProvider(codeRecord);
    String nonce = request.nonce();
    String web3authJwt =
        jwtService.issueWeb3AuthJwt(providerSub, nonce, authProperties.getWeb3authJwtTtlSeconds());
    String accessToken = jwtService.issueAccessToken(walletSub, authProperties.getAccessTokenTtlSeconds());
    String refreshToken = AuthUtils.randomBase64Url(48);

    long now = now();
    String refreshHash = jwtService.sha256Hex(refreshToken);
    RefreshTokenRecord refreshRecord =
        new RefreshTokenRecord(
            UUID.randomUUID().toString(),
            walletSub,
            refreshHash,
            now,
            now + authProperties.getRefreshTokenTtlSeconds() * 1000,
            null,
            null);
    store.putRefreshToken(refreshRecord, authProperties.getRefreshTokenTtlSeconds());

    return new AuthDtos.ExchangeResponse(
        walletSub,
        codeRecord.provider(),
        codeRecord.providerUserId(),
        codeRecord.providerSub(),
        web3authJwt,
        accessToken,
        refreshToken,
        authProperties.getAccessTokenTtlSeconds(),
        authProperties.getRefreshTokenTtlSeconds());
  }

  public AuthDtos.Web3authJwtResponse web3authJwt(AuthDtos.ExchangeRequest request) {
    ensureAuthEnabled();
    AuthCodeRecord codeRecord = consumeValidAuthCode(request.code());
    String providerSub = codeRecord.providerSub();
    String nonce = request.nonce();
    String web3authJwt =
        jwtService.issueWeb3AuthJwt(providerSub, nonce, authProperties.getWeb3authJwtTtlSeconds());
    return new AuthDtos.Web3authJwtResponse(
        codeRecord.provider(), codeRecord.providerUserId(), providerSub, web3authJwt);
  }

  public AuthDtos.SiweNonceResponse siweNonce(
      AuthDtos.SiweNonceRequest request, String ip, String deviceId, String externalBaseUrl) {
    ensureAuthEnabled();
    riskService.checkIpAllowed(ip);
    riskService.checkLoginRateLimits(ip, deviceId);

    String address = normalizeEvmAddressLower(request.address());
    String baseUrl = emptyToNull(externalBaseUrl);
    if (baseUrl == null) {
      throw new AuthException(AuthErrorCode.BAD_REQUEST, "missing base url", 400);
    }

    URI parsed;
    try {
      parsed = URI.create(baseUrl);
    } catch (Exception e) {
      throw new AuthException(AuthErrorCode.BAD_REQUEST, "invalid base url", 400);
    }
    String domain = emptyToNull(parsed.getHost());
    if (domain == null) {
      throw new AuthException(AuthErrorCode.BAD_REQUEST, "invalid base url", 400);
    }

    long now = now();
    long ttlSeconds = Math.max(60, authProperties.getSiweNonceTtlSeconds());
    String nonce = AuthUtils.randomBase64Url(24);
    long expiresAt = now + ttlSeconds * 1000;
    store.putSiweNonce(
        new SiweNonceRecord(nonce, address, domain.trim(), baseUrl, now, expiresAt), ttlSeconds);

    Instant issuedAt = Instant.ofEpochMilli(now);
    Instant expirationTime = Instant.ofEpochMilli(expiresAt);
    return new AuthDtos.SiweNonceResponse(
        nonce,
        domain.trim(),
        baseUrl,
        authProperties.getSiweStatement(),
        1,
        issuedAt.toString(),
        expirationTime.toString());
  }

  public AuthDtos.SiweVerifyResponse siweVerify(
      AuthDtos.SiweVerifyRequest request, String ip, String deviceId) {
    ensureAuthEnabled();
    riskService.checkIpAllowed(ip);
    riskService.checkLoginRateLimits(ip, deviceId);

    SiweUtils.ParsedSiwe parsed;
    try {
      parsed = SiweUtils.parseMessage(request.message());
    } catch (IllegalArgumentException e) {
      metrics.loginFailure("siwe", "message_invalid");
      throw new AuthException(AuthErrorCode.SIWE_MESSAGE_INVALID, "invalid siwe message", 400);
    }

    if (parsed.chainId() != 1) {
      metrics.loginFailure("siwe", "chain_unsupported");
      throw new AuthException(AuthErrorCode.SIWE_MESSAGE_INVALID, "unsupported chain", 400);
    }

    String address = normalizeEvmAddressLower(parsed.address());
    String nonce = parsed.nonce().trim();
    SiweNonceRecord record =
        store
            .consumeSiweNonce(nonce)
            .orElseThrow(
                () -> {
                  metrics.loginFailure("siwe", "nonce_invalid");
                  return new AuthException(AuthErrorCode.SIWE_NONCE_INVALID, "invalid nonce", 401);
                });

    long now = now();
    if (record.expiresAt() < now) {
      metrics.loginFailure("siwe", "nonce_expired");
      throw new AuthException(AuthErrorCode.SIWE_NONCE_EXPIRED, "nonce expired", 401);
    }
    if (!Objects.equals(record.address(), address)) {
      metrics.loginFailure("siwe", "nonce_mismatch");
      throw new AuthException(AuthErrorCode.SIWE_NONCE_INVALID, "invalid nonce", 401);
    }
    if (!Objects.equals(record.domain(), parsed.domain()) || !Objects.equals(record.uri(), parsed.uri())) {
      metrics.loginFailure("siwe", "domain_mismatch");
      throw new AuthException(AuthErrorCode.SIWE_MESSAGE_INVALID, "invalid siwe message", 400);
    }
    if (parsed.expirationTime().toEpochMilli() < now) {
      metrics.loginFailure("siwe", "message_expired");
      throw new AuthException(AuthErrorCode.SIWE_NONCE_EXPIRED, "nonce expired", 401);
    }

    String recovered;
    try {
      recovered = SiweUtils.recoverAddress(request.message(), request.signature());
    } catch (IllegalArgumentException e) {
      metrics.loginFailure("siwe", "sig_invalid");
      throw new AuthException(AuthErrorCode.SIWE_SIGNATURE_INVALID, "invalid signature", 401);
    }
    if (!Objects.equals(recovered, address)) {
      metrics.loginFailure("siwe", "sig_mismatch");
      throw new AuthException(AuthErrorCode.SIWE_SIGNATURE_INVALID, "invalid signature", 401);
    }

    // Ensure a wallet profile exists for sync storage etc.
    getOrCreateWallet(address);

    String accessToken = jwtService.issueAccessToken(address, authProperties.getAccessTokenTtlSeconds());
    String refreshToken = AuthUtils.randomBase64Url(48);

    String refreshHash = jwtService.sha256Hex(refreshToken);
    RefreshTokenRecord refreshRecord =
        new RefreshTokenRecord(
            UUID.randomUUID().toString(),
            address,
            refreshHash,
            now,
            now + authProperties.getRefreshTokenTtlSeconds() * 1000,
            null,
            null);
    store.putRefreshToken(refreshRecord, authProperties.getRefreshTokenTtlSeconds());

    metrics.loginSuccess("siwe");
    return new AuthDtos.SiweVerifyResponse(
        address,
        accessToken,
        refreshToken,
        authProperties.getAccessTokenTtlSeconds(),
        authProperties.getRefreshTokenTtlSeconds());
  }

  public AuthDtos.RefreshResponse refresh(AuthDtos.RefreshRequest request) {
    ensureAuthEnabled();
    String tokenHash = jwtService.sha256Hex(request.refreshToken());
    RefreshTokenRecord record =
        store
            .getRefreshTokenByHash(tokenHash)
            .orElseThrow(() -> new AuthException(AuthErrorCode.REFRESH_TOKEN_INVALID, "invalid refresh token", 401));
    if (record.revokedAt() != null || record.expiresAt() < now()) {
      store.consumeRefreshTokenByHash(record.tokenHash());
      throw new AuthException(AuthErrorCode.REFRESH_TOKEN_INVALID, "refresh token expired", 401);
    }

    String accessToken =
        jwtService.issueAccessToken(record.walletSub(), authProperties.getAccessTokenTtlSeconds());
    String newRefreshToken = AuthUtils.randomBase64Url(48);
    String newHash = jwtService.sha256Hex(newRefreshToken);
    long now = now();
    RefreshTokenRecord next =
        new RefreshTokenRecord(
            UUID.randomUUID().toString(),
            record.walletSub(),
            newHash,
            now,
            now + authProperties.getRefreshTokenTtlSeconds() * 1000,
            null,
            record.id());
    store.putRefreshToken(next, authProperties.getRefreshTokenTtlSeconds());
    store.consumeRefreshTokenByHash(tokenHash);
    return new AuthDtos.RefreshResponse(
        accessToken,
        newRefreshToken,
        authProperties.getAccessTokenTtlSeconds(),
        authProperties.getRefreshTokenTtlSeconds());
  }

  public AuthDtos.MeResponse me(String authorizationHeader) {
    String walletSub = requireWalletSubFromAccessToken(authorizationHeader);
    WalletProfile profile = getOrCreateWallet(walletSub);
    List<ProviderBinding> providers =
        profile.providers().values().stream()
            .sorted(Comparator.comparingLong(ProviderBinding::addedAt))
            .toList();
    return new AuthDtos.MeResponse(walletSub, providers);
  }

  public AuthDtos.MeResponse bindProvider(String authorizationHeader, AuthDtos.BindRequest request) {
    ensureAuthEnabled();
    ensureBindEnabled();
    String walletSub = requireWalletSubFromAccessToken(authorizationHeader);
    riskService.checkBindRateLimit(walletSub);

    AuthCodeRecord codeRecord = consumeValidAuthCode(request.authCode());
    String providerSub = codeRecord.providerSub();

    Optional<String> existingOwner = store.getWalletSubByProviderSub(providerSub);
    if (existingOwner.isPresent() && !existingOwner.get().equals(walletSub)) {
      metrics.bindFailure("conflict");
      throw new AuthException(AuthErrorCode.BIND_CONFLICT, "provider already bound by another account", 409);
    }

    WalletProfile profile = getOrCreateWallet(walletSub);
    if (!profile.providers().containsKey(providerSub)) {
      Map<String, ProviderBinding> providers = new LinkedHashMap<>(profile.providers());
      providers.put(
          providerSub,
          new ProviderBinding(
              codeRecord.provider(),
              codeRecord.providerUserId(),
              providerSub,
              now()));
      profile =
          new WalletProfile(
              profile.walletSub(),
              profile.createdAt(),
              providers,
              profile.favorites(),
              profile.history());
      store.putWalletProfile(profile);
      store.bindProviderSubForce(providerSub, walletSub);
    }
    metrics.bindSuccess();
    return meFromProfile(profile);
  }

  public AuthDtos.MeResponse unbindProvider(
      String authorizationHeader, AuthDtos.UnbindRequest request) {
    ensureAuthEnabled();
    ensureBindEnabled();
    String walletSub = requireWalletSubFromAccessToken(authorizationHeader);
    WalletProfile profile = getOrCreateWallet(walletSub);

    String providerSub = request.providerSub().trim();
    if (!profile.providers().containsKey(providerSub)) {
      throw new AuthException(AuthErrorCode.BAD_REQUEST, "provider not bound", 400);
    }
    if (profile.providers().size() <= 1) {
      metrics.unbindFailure("last_provider");
      throw new AuthException(AuthErrorCode.UNBIND_LAST_PROVIDER, "cannot unbind last provider", 400);
    }

    Map<String, ProviderBinding> providers = new LinkedHashMap<>(profile.providers());
    providers.remove(providerSub);
    WalletProfile next =
        new WalletProfile(
            profile.walletSub(),
            profile.createdAt(),
            providers,
            profile.favorites(),
            profile.history());
    store.putWalletProfile(next);
    store.unbindProviderSub(providerSub);
    metrics.unbindSuccess();
    return meFromProfile(next);
  }

  public AuthDtos.SyncPayloadResponse getSync(String authorizationHeader) {
    ensureAuthEnabled();
    ensureSyncEnabled();
    String walletSub = requireWalletSubFromAccessToken(authorizationHeader);
    WalletProfile profile = getOrCreateWallet(walletSub);
    return new AuthDtos.SyncPayloadResponse(profile.favorites(), profile.history());
  }

  public AuthDtos.SyncPayloadResponse upsertSync(
      String authorizationHeader, AuthDtos.SyncPayloadInput payload) {
    ensureAuthEnabled();
    ensureSyncEnabled();
    String walletSub = requireWalletSubFromAccessToken(authorizationHeader);
    try {
      WalletProfile profile = getOrCreateWallet(walletSub);
      SyncFavorites favorites =
          mergeFavorites(
              profile.favorites(),
              payload.favorites() == null ? List.of() : payload.favorites(),
              payload.favoritesUpdatedAt());
      SyncHistory history =
          mergeHistory(
              profile.history(),
              payload.history() == null ? List.of() : payload.history(),
              payload.historyUpdatedAt());
      WalletProfile next =
          new WalletProfile(profile.walletSub(), profile.createdAt(), profile.providers(), favorites, history);
      store.putWalletProfile(next);
      return new AuthDtos.SyncPayloadResponse(next.favorites(), next.history());
    } catch (AuthException e) {
      metrics.syncError(e.getCode().name());
      throw e;
    } catch (Exception e) {
      metrics.syncError("unknown");
      throw new AuthException(AuthErrorCode.SYNC_PAYLOAD_INVALID, "sync payload invalid", 400);
    }
  }

  public Map<String, Object> jwks() {
    return jwtService.web3AuthJwksJson();
  }

  public String callbackRedirectUrl(String appRedirectUri, AuthDtos.AuthCodeResponse response, String state) {
    UriComponentsBuilder b =
        UriComponentsBuilder.fromUriString(appRedirectUri)
            .queryParam("provider", response.provider())
            .queryParam("providerUserId", response.providerUserId())
            .queryParam("providerSub", response.providerSub())
            .queryParam("authCode", response.code());
    if (StringUtils.hasText(state)) {
      b = b.queryParam("state", state);
    }
    return b.build(true).toUriString();
  }

  public String tryResolveXAppRedirectUri(String state) {
    String stateSecret = authProperties.getX().getStateSecret();
    if (!StringUtils.hasText(stateSecret)) return null;
    if (!XOAuthStateTokens.looksLikeToken(state)) return null;

    XOAuthStateTokens.Parsed parsed =
        XOAuthStateTokens.parseAndVerify(objectMapper, stateSecret, state, now());
    if (parsed == null) return null;
    String appRedirectUri = parsed.appRedirectUri();
    if (!StringUtils.hasText(appRedirectUri)) return null;
    try {
      validateAppRedirect(appRedirectUri);
    } catch (Exception ignored) {
      return null;
    }
    return appRedirectUri;
  }

  public String callbackErrorRedirectUrl(String appRedirectUri, AuthException error, String state) {
    UriComponentsBuilder b =
        UriComponentsBuilder.fromUriString(appRedirectUri)
            .queryParam("errorCode", error == null ? "UNKNOWN" : error.getCode().name())
            .queryParam("errorDescription", error == null ? "unknown error" : error.getMessage());
    if (error != null && error.getRetryAfterSeconds() != null && error.getRetryAfterSeconds() > 0) {
      b = b.queryParam("retryAfterSeconds", error.getRetryAfterSeconds());
    }
    if (StringUtils.hasText(state)) {
      b = b.queryParam("state", state);
    }
    if (error != null && error.getDetails() != null) {
      Object resumeToken = error.getDetails().get("resumeToken");
      if (resumeToken instanceof String s && StringUtils.hasText(s)) {
        b = b.queryParam("resumeToken", s);
      }
    }
    return b.build().encode().toUriString();
  }

  private SyncFavorites mergeFavorites(
      SyncFavorites current, List<FavoriteItem> incoming, Long incomingUpdatedAt) {
    Map<String, FavoriteItem> merged = new LinkedHashMap<>();
    long maxUpdatedAt = current.updatedAt();

    for (FavoriteItem item : current.items()) {
      FavoriteItem normalized = normalizeFavoriteItem(item);
      if (normalized == null) continue;
      merged.put(normalizeUrlKey(normalized.url()), normalized);
      maxUpdatedAt = Math.max(maxUpdatedAt, normalized.updatedAt());
    }
    for (FavoriteItem item : incoming) {
      FavoriteItem normalized = normalizeFavoriteItem(item);
      if (normalized == null) continue;
      String key = normalizeUrlKey(normalized.url());
      FavoriteItem existing = merged.get(key);
      if (existing == null || normalized.updatedAt() >= existing.updatedAt()) {
        merged.put(key, normalized);
      }
      maxUpdatedAt = Math.max(maxUpdatedAt, normalized.updatedAt());
    }

    if (incomingUpdatedAt != null) maxUpdatedAt = Math.max(maxUpdatedAt, incomingUpdatedAt);
    List<FavoriteItem> items =
        merged.values().stream()
            .sorted(Comparator.comparingLong(FavoriteItem::updatedAt).reversed())
            .limit(MAX_FAVORITES)
            .toList();
    return new SyncFavorites(items, maxUpdatedAt);
  }

  private SyncHistory mergeHistory(SyncHistory current, List<HistoryItem> incoming, Long incomingUpdatedAt) {
    Map<String, HistoryItem> merged = new LinkedHashMap<>();
    long maxUpdatedAt = current.updatedAt();
    for (HistoryItem item : current.items()) {
      HistoryItem normalized = normalizeHistoryItem(item);
      if (normalized == null) continue;
      merged.put(normalizeUrlKey(normalized.url()), normalized);
      maxUpdatedAt = Math.max(maxUpdatedAt, normalized.updatedAt());
    }
    for (HistoryItem item : incoming) {
      HistoryItem normalized = normalizeHistoryItem(item);
      if (normalized == null) continue;
      String key = normalizeUrlKey(normalized.url());
      HistoryItem existing = merged.get(key);
      if (existing == null || normalized.updatedAt() >= existing.updatedAt()) {
        merged.put(key, normalized);
      }
      maxUpdatedAt = Math.max(maxUpdatedAt, normalized.updatedAt());
    }
    if (incomingUpdatedAt != null) maxUpdatedAt = Math.max(maxUpdatedAt, incomingUpdatedAt);
    List<HistoryItem> items =
        merged.values().stream()
            .sorted(Comparator.comparingLong(HistoryItem::updatedAt).reversed())
            .limit(MAX_HISTORY)
            .toList();
    return new SyncHistory(items, maxUpdatedAt);
  }

  private FavoriteItem normalizeFavoriteItem(FavoriteItem item) {
    if (item == null || !StringUtils.hasText(item.url())) return null;
    long updatedAt = item.updatedAt() > 0 ? item.updatedAt() : now();
    String url = item.url().trim();
    return new FavoriteItem(url, emptyToNull(item.name()), emptyToNull(item.iconUrl()), updatedAt, item.deletedAt());
  }

  private HistoryItem normalizeHistoryItem(HistoryItem item) {
    if (item == null || !StringUtils.hasText(item.url())) return null;
    long nowTs = now();
    long updatedAt = item.updatedAt() > 0 ? item.updatedAt() : nowTs;
    long visitedAt = item.visitedAt() > 0 ? item.visitedAt() : updatedAt;
    String url = item.url().trim();
    return new HistoryItem(
        url, emptyToNull(item.title()), emptyToNull(item.iconUrl()), visitedAt, updatedAt, item.deletedAt());
  }

  private String normalizeUrlKey(String url) {
    return url.trim().toLowerCase(Locale.ROOT);
  }

  private WalletProfile getOrCreateWallet(String walletSub) {
    return store
        .getWalletProfile(walletSub)
        .orElseGet(
            () -> {
              WalletProfile created = WalletProfile.create(walletSub, now());
              store.putWalletProfile(created);
              return created;
            });
  }

  private static String normalizeEvmAddressLower(String input) {
    String raw = input == null ? "" : input.trim();
    String hex = raw.startsWith("0x") || raw.startsWith("0X") ? raw.substring(2) : raw;
    if (hex.length() != 40 || !hex.matches("^[0-9a-fA-F]{40}$")) {
      throw new AuthException(AuthErrorCode.BAD_REQUEST, "invalid address", 400);
    }
    return ("0x" + hex).toLowerCase(Locale.ROOT);
  }

  private AuthCodeRecord consumeValidAuthCode(String code) {
    long now = now();
    AuthCodeRecord record =
        store
            .getAuthCode(code)
            .orElseThrow(() -> new AuthException(AuthErrorCode.AUTH_CODE_INVALID, "invalid auth code", 401));
    long remainingMs = record.expiresAt() - now;
    long ttlSeconds = Math.max(1, (remainingMs + 999) / 1000);
    boolean firstUse = store.markAuthCodeUsedOnce(record.code(), ttlSeconds);
    if (!firstUse) {
      throw new AuthException(AuthErrorCode.AUTH_CODE_USED, "auth code already used", 401);
    }
    if (record.usedAt() != null) {
      throw new AuthException(AuthErrorCode.AUTH_CODE_USED, "auth code already used", 401);
    }
    if (record.expiresAt() < now) {
      throw new AuthException(AuthErrorCode.AUTH_CODE_EXPIRED, "auth code expired", 401);
    }
    AuthCodeRecord used =
        new AuthCodeRecord(
            record.code(),
            record.provider(),
            record.providerUserId(),
            record.providerSub(),
            record.createdAt(),
            record.expiresAt(),
            now);
    store.updateAuthCode(used);
    return used;
  }

  private String resolveOrCreateWalletForProvider(AuthCodeRecord codeRecord) {
    String providerSub = codeRecord.providerSub();
    Optional<String> existingWallet = store.getWalletSubByProviderSub(providerSub);
    String walletSub;
    if (existingWallet.isPresent()) {
      walletSub = existingWallet.get();
    } else {
      String proposed = AuthUtils.randomWalletSub();
      boolean created = store.bindProviderSubIfAbsent(providerSub, proposed);
      walletSub = created ? proposed : store.getWalletSubByProviderSub(providerSub).orElse(proposed);
    }

    WalletProfile profile = getOrCreateWallet(walletSub);
    if (!profile.providers().containsKey(providerSub)) {
      Map<String, ProviderBinding> providers = new LinkedHashMap<>(profile.providers());
      providers.put(
          providerSub,
          new ProviderBinding(
              codeRecord.provider(),
              codeRecord.providerUserId(),
              codeRecord.providerSub(),
              now()));
      profile =
          new WalletProfile(
              profile.walletSub(),
              profile.createdAt(),
              providers,
              profile.favorites(),
              profile.history());
      store.putWalletProfile(profile);
      store.bindProviderSubForce(providerSub, walletSub);
    } else if (store.getWalletSubByProviderSub(providerSub).isEmpty()) {
      store.bindProviderSubForce(providerSub, walletSub);
    }
    return walletSub;
  }

  private AuthCodeRecord issueAuthCode(
      AuthProvider provider, String providerUserId, String providerSub) {
    long now = now();
    AuthCodeRecord authCode =
        new AuthCodeRecord(
            AuthUtils.randomBase64Url(24),
            provider.code(),
            providerUserId,
            providerSub,
            now,
            now + authProperties.getOneTimeCodeTtlSeconds() * 1000,
            null);
    store.putAuthCode(authCode, authProperties.getOneTimeCodeTtlSeconds());
    return authCode;
  }

  private void validateXConfig() {
    AuthProperties.X x = authProperties.getX();
    if (!StringUtils.hasText(x.getClientId())
        || !StringUtils.hasText(x.getRedirectUri())
        || !StringUtils.hasText(x.getAuthorizeEndpoint())
        || !StringUtils.hasText(x.getTokenEndpoint())
        || !StringUtils.hasText(x.getUserinfoEndpoint())) {
      throw new AuthException(AuthErrorCode.PROVIDER_UNAVAILABLE, "x oauth not configured", 503);
    }
  }

  private void validateAppRedirect(String appRedirectUri) {
    if (!StringUtils.hasText(appRedirectUri)) return;
    if (!AuthUtils.isAllowedRedirect(appRedirectUri, authProperties.appRedirectAllowPrefixes())) {
      throw new AuthException(AuthErrorCode.BAD_REQUEST, "appRedirectUri not allowed", 400);
    }
  }

  private String requireWalletSubFromAccessToken(String authorizationHeader) {
    String token = extractBearerToken(authorizationHeader);
    AuthJwtService.AccessTokenClaims claims = jwtService.verifyAccessToken(token);
    return claims.walletSub();
  }

  private String extractBearerToken(String authorizationHeader) {
    if (authorizationHeader == null || authorizationHeader.isBlank()) {
      throw new AuthException(AuthErrorCode.UNAUTHORIZED, "missing authorization header", 401);
    }
    String value = authorizationHeader.trim();
    if (!value.regionMatches(true, 0, "Bearer ", 0, 7)) {
      throw new AuthException(AuthErrorCode.UNAUTHORIZED, "invalid authorization type", 401);
    }
    String token = value.substring(7).trim();
    if (token.isBlank()) {
      throw new AuthException(AuthErrorCode.UNAUTHORIZED, "missing access token", 401);
    }
    return token;
  }

  private AuthDtos.MeResponse meFromProfile(WalletProfile profile) {
    List<ProviderBinding> providers =
        profile.providers().values().stream()
            .sorted(Comparator.comparingLong(ProviderBinding::addedAt))
            .toList();
    return new AuthDtos.MeResponse(profile.walletSub(), providers);
  }

  private void ensureAuthEnabled() {
    if (!authProperties.isEnabled()) {
      throw new AuthException(AuthErrorCode.FEATURE_DISABLED, "auth feature disabled", 403);
    }
  }

  private void ensureXEnabled() {
    if (!authProperties.isXEnabled()) {
      throw new AuthException(AuthErrorCode.FEATURE_DISABLED, "x login disabled", 403);
    }
  }

  private void ensureTgEnabled() {
    if (!authProperties.isTgEnabled()) {
      throw new AuthException(AuthErrorCode.FEATURE_DISABLED, "telegram login disabled", 403);
    }
  }

  private void ensureBindEnabled() {
    if (!authProperties.isBindEnabled()) {
      throw new AuthException(AuthErrorCode.FEATURE_DISABLED, "provider bind disabled", 403);
    }
  }

  private void ensureSyncEnabled() {
    if (!authProperties.isSyncEnabled()) {
      throw new AuthException(AuthErrorCode.FEATURE_DISABLED, "sync disabled", 403);
    }
  }

  private long now() {
    return Instant.now().toEpochMilli();
  }

  private static String emptyToNull(String value) {
    if (value == null) return null;
    String v = value.trim();
    return v.isEmpty() ? null : v;
  }

  public record XCallbackResult(AuthDtos.AuthCodeResponse payload, String appRedirectUri, String state) {}
}
