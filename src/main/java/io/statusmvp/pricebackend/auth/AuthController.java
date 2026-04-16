package io.statusmvp.pricebackend.auth;

import io.statusmvp.pricebackend.auth.dto.AuthDtos;
import jakarta.validation.Valid;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@Validated
public class AuthController {
  private final AuthService authService;
  private final AuthProperties authProperties;
  private final AuthDeviceProofService authDeviceProofService;
  private final AuthAppAttestService authAppAttestService;
  private final AuthPlayIntegrityService authPlayIntegrityService;

  public AuthController(
      AuthService authService,
      AuthProperties authProperties,
      AuthDeviceProofService authDeviceProofService,
      AuthAppAttestService authAppAttestService,
      AuthPlayIntegrityService authPlayIntegrityService) {
    this.authService = authService;
    this.authProperties = authProperties;
    this.authDeviceProofService = authDeviceProofService;
    this.authAppAttestService = authAppAttestService;
    this.authPlayIntegrityService = authPlayIntegrityService;
  }

  @GetMapping(path = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<ResponseEntity<java.util.Map<String, Object>>> jwks() {
    return Mono.fromCallable(() -> ResponseEntity.ok(authService.jwks()))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @PostMapping(path = "/api/v1/auth/device-proof/challenge", produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<AuthDtos.DeviceProofChallengeResponse> deviceProofChallenge(
      @Valid @RequestBody AuthDtos.DeviceProofChallengeRequest request,
      @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
      @RequestHeader(value = "X-App-Platform", required = false) String platform) {
    return Mono.fromCallable(() -> authDeviceProofService.issueChallenge(request, deviceId, platform))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @PostMapping(path = "/api/v1/auth/app-attest/challenge", produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<AuthDtos.AppAttestChallengeResponse> appAttestChallenge(
      @RequestBody(required = false) AuthDtos.AppAttestChallengeRequest request,
      @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
      @RequestHeader(value = "X-App-Platform", required = false) String platform) {
    return Mono.fromCallable(
            () ->
                authAppAttestService.issueChallenge(
                    request == null ? new AuthDtos.AppAttestChallengeRequest(null) : request,
                    deviceId,
                    platform))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @PostMapping(path = "/api/v1/auth/app-attest/register", produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<AuthDtos.AppAttestRegisterResponse> appAttestRegister(
      @Valid @RequestBody AuthDtos.AppAttestRegisterRequest request,
      @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
      @RequestHeader(value = "X-App-Platform", required = false) String platform) {
    return Mono.fromCallable(() -> authAppAttestService.registerAttestation(request, deviceId, platform))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @PostMapping(path = "/api/v1/auth/app-attest/assertion/challenge", produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<AuthDtos.AppAttestAssertionChallengeResponse> appAttestAssertionChallenge(
      @Valid @RequestBody AuthDtos.AppAttestAssertionChallengeRequest request,
      @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
      @RequestHeader(value = "X-App-Platform", required = false) String platform) {
    return Mono.fromCallable(() -> authAppAttestService.issueAssertionChallenge(request, deviceId, platform))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @GetMapping(path = "/api/v1/auth/x/start", produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<AuthDtos.XStartResponse> startX(
      @RequestParam(value = "appRedirectUri", required = false) String appRedirectUri,
      @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
      @RequestHeader(value = "X-App-Platform", required = false) String platform,
      ServerWebExchange exchange) {
    return Mono.fromCallable(
            () -> {
                requireSecureAndroidSocialAuthChannel(platform, exchange, "x_start");
                return authService.startXLogin(
                    appRedirectUri,
                    deviceId,
                    verifiedDeviceProofKeyId(
                        deviceId,
                        platform,
                        "GET",
                        "/api/v1/auth/x/start",
                        exchange));
            })
        .subscribeOn(Schedulers.boundedElastic());
  }

  @GetMapping(path = "/api/v1/auth/tg/start", produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<AuthDtos.OAuthStartResponse> startTelegram(
      @RequestParam(value = "appRedirectUri", required = false) String appRedirectUri,
      ServerWebExchange exchange,
      @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
      @RequestHeader(value = "X-App-Platform", required = false) String platform) {
    return Mono.fromCallable(
            () -> {
                requireSecureAndroidSocialAuthChannel(platform, exchange, "telegram_start");
                return authService.startTelegramLogin(
                    appRedirectUri,
                    deviceId,
                    resolveExternalBaseUrl(exchange),
                    verifiedDeviceProofKeyId(
                        deviceId,
                        platform,
                        "GET",
                        "/api/v1/auth/tg/start",
                        exchange));
            })
        .subscribeOn(Schedulers.boundedElastic());
  }

  @GetMapping(path = "/api/v1/auth/tg/login")
  public Mono<ResponseEntity<?>> telegramLoginPage(
      @RequestParam(value = "state", required = false) String state,
      @RequestParam(value = "nonce", required = false) String nonce,
      @RequestParam(value = "code", required = false) String code,
      @RequestParam(value = "error", required = false) String error,
      @RequestParam(value = "error_description", required = false) String errorDescription,
      @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
      ServerWebExchange exchange) {
    return Mono.fromCallable(
            () -> {
              if (StringUtils.hasText(code) || StringUtils.hasText(error)) {
                String ip = resolveClientIp(exchange);
                try {
                  AuthService.XCallbackResult result =
                      authService.handleTelegramCallback(code, state, error, errorDescription, ip, deviceId);
                  if (result.appRedirectUri() != null && !result.appRedirectUri().isBlank()) {
                    String location =
                        authService.callbackRedirectUrl(
                            result.appRedirectUri(), result.payload(), result.state());
                    return ResponseEntity.status(HttpStatus.FOUND)
                        .header(HttpHeaders.LOCATION, location)
                        .build();
                  }
                  return ResponseEntity.ok(result.payload());
                } catch (AuthException e) {
                  String appRedirectUri = authService.tryResolveTelegramAppRedirectUri(state);
                  if (appRedirectUri != null && !appRedirectUri.isBlank()) {
                    String location = authService.callbackErrorRedirectUrl(appRedirectUri, e, state);
                    return ResponseEntity.status(HttpStatus.FOUND)
                        .header(HttpHeaders.LOCATION, location)
                        .build();
                  }
                  throw e;
                }
              }
              String clientId = authService.telegramLoginClientId();
              if (!StringUtils.hasText(state) || !StringUtils.hasText(nonce)) {
                throw new AuthException(AuthErrorCode.BAD_REQUEST, "telegram login state missing", 400);
              }
              String html = telegramLoginHtml(clientId.trim(), state.trim(), nonce.trim());
              return ResponseEntity.ok()
                  .contentType(MediaType.TEXT_HTML)
                  .header(HttpHeaders.CACHE_CONTROL, "no-store")
                  .body(html);
            })
        .subscribeOn(Schedulers.boundedElastic());
  }

  @GetMapping(path = "/api/v1/auth/tg/widget", produces = MediaType.TEXT_HTML_VALUE)
  public Mono<ResponseEntity<String>> telegramWidget(
      @RequestParam(value = "appRedirectUri") String appRedirectUri,
      @RequestParam(value = "state", required = false) String state,
      ServerWebExchange exchange) {
    return Mono.fromCallable(
            () -> {
              authService.validateTgWidgetRequest(appRedirectUri);
              String botUsername = authService.telegramBotUsername();
              if (!StringUtils.hasText(botUsername)) {
                throw new AuthException(AuthErrorCode.PROVIDER_UNAVAILABLE, "telegram bot username not configured", 503);
              }

              String baseUrl = resolveExternalBaseUrl(exchange);
              UriComponentsBuilder b =
                  UriComponentsBuilder.fromHttpUrl(baseUrl)
                      .path("/api/v1/auth/tg/widget/callback")
                      .queryParam("appRedirectUri", appRedirectUri);
              if (StringUtils.hasText(state)) {
                b = b.queryParam("state", state);
              }
              String callbackUrl = b.build(true).toUriString();
              String html = telegramWidgetHtml(botUsername.trim(), callbackUrl);
              return ResponseEntity.ok()
                  .contentType(MediaType.TEXT_HTML)
                  .header(HttpHeaders.CACHE_CONTROL, "no-store")
                  .body(html);
            })
        .subscribeOn(Schedulers.boundedElastic());
  }

  @GetMapping(path = "/api/v1/auth/tg/widget/callback", produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<ResponseEntity<?>> telegramWidgetCallback(
      @RequestParam(value = "appRedirectUri") String appRedirectUri,
      @RequestParam(value = "state", required = false) String state,
      @RequestParam(value = "id", required = false) String id,
      @RequestParam(value = "first_name", required = false) String firstName,
      @RequestParam(value = "last_name", required = false) String lastName,
      @RequestParam(value = "username", required = false) String username,
      @RequestParam(value = "photo_url", required = false) String photoUrl,
      @RequestParam(value = "auth_date", required = false) String authDate,
      @RequestParam(value = "hash", required = false) String hash,
      @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
      ServerWebExchange exchange) {
    return Mono.<ResponseEntity<?>>fromCallable(
            () -> {
              String ip = resolveClientIp(exchange);
              try {
                AuthService.TelegramWidgetStartContext widgetContext =
                    authService.consumeTelegramWidgetStartContext(state, deviceId, appRedirectUri);
                AuthDtos.TelegramLoginRequest request =
                    new AuthDtos.TelegramLoginRequest(
                        id == null ? "" : id,
                        firstName,
                        lastName,
                        username,
                        photoUrl,
                        authDate == null ? "" : authDate,
                        hash == null ? "" : hash,
                        widgetContext.appRedirectUri());
                AuthDtos.AuthCodeResponse result =
                    authService.telegramLogin(
                        request, ip, widgetContext.deviceId(), widgetContext.deviceProofKeyId());
                String location =
                    authService.callbackRedirectUrl(widgetContext.appRedirectUri(), result, state);
                return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, location).build();
              } catch (AuthException e) {
                try {
                  authService.validateAppRedirectUri(appRedirectUri);
                  String location = authService.callbackErrorRedirectUrl(appRedirectUri, e, state);
                  return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, location).build();
                } catch (Exception ignored) {
                  // If redirect is invalid, fall back to JSON error below.
                }
                throw e;
              }
            })
        .subscribeOn(Schedulers.boundedElastic());
  }

  @GetMapping(path = "/api/v1/auth/x/callback", produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<ResponseEntity<?>> xCallback(
      @RequestParam(value = "code", required = false) String code,
      @RequestParam(value = "state", required = false) String state,
      @RequestParam(value = "error", required = false) String error,
      @RequestParam(value = "error_description", required = false) String errorDescription,
      @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
      ServerWebExchange exchange) {
    return Mono.fromCallable(
            () -> {
              String ip = resolveClientIp(exchange);
              try {
                AuthService.XCallbackResult result =
                    authService.handleXCallback(code, state, error, errorDescription, ip, deviceId);
                if (result.appRedirectUri() != null && !result.appRedirectUri().isBlank()) {
                  String location =
                      authService.callbackRedirectUrl(
                          result.appRedirectUri(), result.payload(), result.state());
                  return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, location).build();
                }
                return ResponseEntity.ok(result.payload());
              } catch (AuthException e) {
                String appRedirectUri = authService.tryResolveXAppRedirectUri(state);
                if (appRedirectUri != null && !appRedirectUri.isBlank()) {
                  String location = authService.callbackErrorRedirectUrl(appRedirectUri, e, state);
                  return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, location).build();
                }
                throw e;
              }
            })
        .subscribeOn(Schedulers.boundedElastic());
  }

  @GetMapping(path = "/api/v1/auth/tg/callback", produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<ResponseEntity<?>> telegramCallback(
      @RequestParam(value = "code", required = false) String code,
      @RequestParam(value = "state", required = false) String state,
      @RequestParam(value = "error", required = false) String error,
      @RequestParam(value = "error_description", required = false) String errorDescription,
      @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
      ServerWebExchange exchange) {
    return Mono.fromCallable(
            () -> {
              String ip = resolveClientIp(exchange);
              try {
                AuthService.XCallbackResult result =
                    authService.handleTelegramCallback(code, state, error, errorDescription, ip, deviceId);
                if (result.appRedirectUri() != null && !result.appRedirectUri().isBlank()) {
                  String location =
                      authService.callbackRedirectUrl(
                          result.appRedirectUri(), result.payload(), result.state());
                  return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, location).build();
                }
                return ResponseEntity.ok(result.payload());
              } catch (AuthException e) {
                String appRedirectUri = authService.tryResolveTelegramAppRedirectUri(state);
                if (appRedirectUri != null && !appRedirectUri.isBlank()) {
                  String location = authService.callbackErrorRedirectUrl(appRedirectUri, e, state);
                  return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, location).build();
                }
                throw e;
              }
            })
        .subscribeOn(Schedulers.boundedElastic());
  }

  @PostMapping(path = "/api/v1/auth/x/resume", produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<AuthDtos.AuthCodeResponse> xResume(
      @Valid @RequestBody AuthDtos.XResumeRequest request,
      @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
      @RequestHeader(value = "X-App-Platform", required = false) String platform,
      ServerWebExchange exchange) {
    return Mono.fromCallable(
            () -> {
                requireSecureAndroidSocialAuthChannel(platform, exchange, "x_resume");
                return authService.resumeXLogin(
                    request.resumeToken(),
                    resolveClientIp(exchange),
                    deviceId,
                    verifiedDeviceProofKeyId(
                        deviceId,
                        platform,
                        "POST",
                        "/api/v1/auth/x/resume",
                        exchange));
            })
        .subscribeOn(Schedulers.boundedElastic());
  }

  @PostMapping(path = "/api/v1/auth/tg/login", produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<AuthDtos.AuthCodeResponse> telegramLogin(
      @Valid @RequestBody AuthDtos.TelegramLoginRequest request,
      @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
      @RequestHeader(value = "X-App-Platform", required = false) String platform,
      ServerWebExchange exchange) {
    return Mono.fromCallable(
            () -> {
                requireSecureAndroidSocialAuthChannel(platform, exchange, "telegram_login");
                return authService.telegramLogin(
                    request,
                    resolveClientIp(exchange),
                    deviceId,
                    verifiedDeviceProofKeyId(
                        deviceId,
                        platform,
                        "POST",
                        "/api/v1/auth/tg/login",
                        exchange));
            })
        .subscribeOn(Schedulers.boundedElastic());
  }

  @PostMapping(path = "/api/v1/auth/apple/login", produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<AuthDtos.AuthCodeResponse> appleLogin(
      @Valid @RequestBody AuthDtos.AppleLoginRequest request,
      @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
      @RequestHeader(value = "X-App-Platform", required = false) String platform,
      ServerWebExchange exchange) {
    return Mono.fromCallable(
            () -> {
                requireSecureAndroidSocialAuthChannel(platform, exchange, "apple_login");
                return authService.appleLogin(
                    request,
                    resolveClientIp(exchange),
                    deviceId,
                    verifiedDeviceProofKeyId(
                        deviceId,
                        platform,
                        "POST",
                        "/api/v1/auth/apple/login",
                        exchange));
            })
        .subscribeOn(Schedulers.boundedElastic());
  }

  @PostMapping(path = "/api/v1/auth/tg/login/complete", produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<AuthDtos.RedirectResponse> telegramLoginComplete(
      @Valid @RequestBody AuthDtos.TelegramOidcCompleteRequest request,
      @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
      ServerWebExchange exchange) {
    return Mono.fromCallable(
            () -> {
              String ip = resolveClientIp(exchange);
              String appRedirectUri = authService.tryResolveTelegramAppRedirectUri(request.state());
              try {
                AuthService.XCallbackResult result =
                    authService.completeTelegramLogin(request.state(), request.idToken(), ip, deviceId);
                String location =
                    authService.callbackRedirectUrl(
                        result.appRedirectUri(), result.payload(), result.state());
                return new AuthDtos.RedirectResponse(location);
              } catch (AuthException e) {
                if (StringUtils.hasText(appRedirectUri)) {
                  String location =
                      authService.callbackErrorRedirectUrl(appRedirectUri, e, request.state());
                  return new AuthDtos.RedirectResponse(location);
                }
                throw e;
              }
            })
        .subscribeOn(Schedulers.boundedElastic());
  }

  @PostMapping(
      path = "/api/v1/auth/tg/login/complete",
      consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
  public Mono<ResponseEntity<Void>> telegramLoginCompleteForm(
      @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
      ServerWebExchange exchange) {
    return exchange
        .getFormData()
        .flatMap(
            form ->
                Mono.<ResponseEntity<Void>>fromCallable(
                        () -> {
                          String state =
                              StringUtils.trimWhitespace(form == null ? null : form.getFirst("state"));
                          String idToken =
                              StringUtils.trimWhitespace(form == null ? null : form.getFirst("idToken"));
                          String ip = resolveClientIp(exchange);
                          String appRedirectUri = authService.tryResolveTelegramAppRedirectUri(state);
                          try {
                            AuthService.XCallbackResult result =
                                authService.completeTelegramLogin(state, idToken, ip, deviceId);
                            String location =
                                authService.callbackRedirectUrl(
                                    result.appRedirectUri(), result.payload(), result.state());
                            return ResponseEntity.status(HttpStatus.FOUND)
                                .header(HttpHeaders.LOCATION, location)
                                .build();
                          } catch (AuthException e) {
                            if (StringUtils.hasText(appRedirectUri)) {
                              String location =
                                  authService.callbackErrorRedirectUrl(appRedirectUri, e, state);
                              return ResponseEntity.status(HttpStatus.FOUND)
                                  .header(HttpHeaders.LOCATION, location)
                                  .build();
                            }
                            throw e;
                          }
                        })
                    .subscribeOn(Schedulers.boundedElastic()));
  }

  @PostMapping(path = "/api/v1/auth/exchange", produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<AuthDtos.ExchangeResponse> exchange(
      @Valid @RequestBody AuthDtos.ExchangeRequest request,
      @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
      @RequestHeader(value = "X-App-Platform", required = false) String platform,
      ServerWebExchange exchange) {
    return Mono.fromCallable(
            () -> {
                requireSecureAndroidSocialAuthChannel(platform, exchange, "exchange");
                return authService.exchange(
                    request,
                    deviceId,
                    verifiedDeviceProofKeyId(
                        deviceId,
                        platform,
                        "POST",
                        "/api/v1/auth/exchange",
                        exchange));
            })
        .subscribeOn(Schedulers.boundedElastic());
  }

  @PostMapping(path = "/api/v1/auth/siwe/nonce", produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<AuthDtos.SiweNonceResponse> siweNonce(
      @Valid @RequestBody AuthDtos.SiweNonceRequest request,
      @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
      @RequestHeader(value = "X-App-Platform", required = false) String platform,
      ServerWebExchange exchange) {
    return Mono.fromCallable(
            () ->
                authService.siweNonce(
                    request,
                    resolveClientIp(exchange),
                    deviceId,
                    resolveExternalBaseUrl(exchange),
                    verifiedDeviceProofKeyId(
                        deviceId,
                        platform,
                        "POST",
                        "/api/v1/auth/siwe/nonce",
                        exchange)))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @PostMapping(path = "/api/v1/auth/siwe/verify", produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<AuthDtos.SiweVerifyResponse> siweVerify(
      @Valid @RequestBody AuthDtos.SiweVerifyRequest request,
      @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
      @RequestHeader(value = "X-App-Platform", required = false) String platform,
      ServerWebExchange exchange) {
    return Mono.fromCallable(
            () ->
                authService.siweVerify(
                    request,
                    resolveClientIp(exchange),
                    deviceId,
                    verifiedDeviceProofKeyId(
                        deviceId,
                        platform,
                        "POST",
                        "/api/v1/auth/siwe/verify",
                        exchange)))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @PostMapping(path = "/api/v1/auth/refresh", produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<AuthDtos.RefreshResponse> refresh(
      @Valid @RequestBody AuthDtos.RefreshRequest request,
      @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
      @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
      @RequestHeader(value = "X-App-Platform", required = false) String platform,
      ServerWebExchange exchange) {
    return Mono.fromCallable(
            () ->
                authService.refresh(
                    request,
                    authorizationHeader,
                    deviceId,
                    verifiedDeviceProofKeyId(
                        deviceId,
                        platform,
                        "POST",
                        "/api/v1/auth/refresh",
                        exchange)))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @PostMapping(path = "/api/v1/auth/logout", produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<AuthDtos.LogoutResponse> logout(
      @Valid @RequestBody AuthDtos.LogoutRequest request,
      @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
      @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
      @RequestHeader(value = "X-App-Platform", required = false) String platform,
      ServerWebExchange exchange) {
    return Mono.fromCallable(
            () ->
                authService.logout(
                    request,
                    authorizationHeader,
                    deviceId,
                    verifiedDeviceProofKeyId(
                        deviceId,
                        platform,
                        "POST",
                        "/api/v1/auth/logout",
                        exchange)))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @GetMapping(path = "/api/v1/auth/me", produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<AuthDtos.MeResponse> me(
      @RequestHeader("Authorization") String authorizationHeader,
      @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
      @RequestHeader(value = "X-App-Platform", required = false) String platform,
      ServerWebExchange exchange) {
    return Mono.fromCallable(
            () -> {
              verifiedDeviceProofKeyId(deviceId, platform, "GET", "/api/v1/auth/me", exchange);
              return authService.me(authorizationHeader);
            })
        .subscribeOn(Schedulers.boundedElastic());
  }

  @PostMapping(path = "/api/v1/auth/account/delete", produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<AuthDtos.DeleteAccountResponse> deleteAccount(
      @RequestHeader("Authorization") String authorizationHeader,
      @RequestBody(required = false) AuthDtos.DeleteAccountRequest request,
      @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
      @RequestHeader(value = "X-App-Platform", required = false) String platform,
      ServerWebExchange exchange) {
    return Mono.fromCallable(
            () ->
                authService.deleteAccount(
                    authorizationHeader,
                    request,
                    deviceId,
                    verifiedDeviceProofKeyId(
                        deviceId,
                        platform,
                        "POST",
                        "/api/v1/auth/account/delete",
                        exchange)))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @PostMapping(path = "/api/v1/auth/providers/bind", produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<AuthDtos.MeResponse> bind(
      @RequestHeader("Authorization") String authorizationHeader,
      @Valid @RequestBody AuthDtos.BindRequest request,
      @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
      @RequestHeader(value = "X-App-Platform", required = false) String platform,
      ServerWebExchange exchange) {
    return Mono.fromCallable(
            () -> {
                requireSecureAndroidSocialAuthChannel(platform, exchange, "bind_provider");
                return authService.bindProvider(
                    authorizationHeader,
                    request,
                    deviceId,
                    verifiedDeviceProofKeyId(
                        deviceId,
                        platform,
                        "POST",
                        "/api/v1/auth/providers/bind",
                        exchange));
            })
        .subscribeOn(Schedulers.boundedElastic());
  }

  @PostMapping(path = "/api/v1/auth/providers/unbind", produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<AuthDtos.MeResponse> unbind(
      @RequestHeader("Authorization") String authorizationHeader,
      @Valid @RequestBody AuthDtos.UnbindRequest request,
      @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
      @RequestHeader(value = "X-App-Platform", required = false) String platform,
      ServerWebExchange exchange) {
    return Mono.fromCallable(
            () -> {
              verifiedDeviceProofKeyId(deviceId, platform, "POST", "/api/v1/auth/providers/unbind", exchange);
              return authService.unbindProvider(authorizationHeader, request);
            })
        .subscribeOn(Schedulers.boundedElastic());
  }

  @GetMapping(path = "/api/v1/auth/sync/dapps", produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<AuthDtos.SyncPayloadResponse> getSync(
      @RequestHeader("Authorization") String authorizationHeader,
      @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
      @RequestHeader(value = "X-App-Platform", required = false) String platform,
      ServerWebExchange exchange) {
    return Mono.fromCallable(
            () -> {
              verifiedDeviceProofKeyId(deviceId, platform, "GET", "/api/v1/auth/sync/dapps", exchange);
              return authService.getSync(authorizationHeader);
            })
        .subscribeOn(Schedulers.boundedElastic());
  }

  @PostMapping(path = "/api/v1/auth/sync/dapps", produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<AuthDtos.SyncPayloadResponse> upsertSync(
      @RequestHeader("Authorization") String authorizationHeader,
      @Valid @RequestBody AuthDtos.SyncPayloadInput payload,
      @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
      @RequestHeader(value = "X-App-Platform", required = false) String platform,
      ServerWebExchange exchange) {
    return Mono.fromCallable(
            () -> {
              verifiedDeviceProofKeyId(deviceId, platform, "POST", "/api/v1/auth/sync/dapps", exchange);
              return authService.upsertSync(authorizationHeader, payload);
            })
        .subscribeOn(Schedulers.boundedElastic());
  }

  private String verifiedDeviceProofKeyId(
      String deviceId,
      String platform,
      String method,
      String path,
      ServerWebExchange exchange) {
    authPlayIntegrityService.verifyProtectedRequest(
        deviceId,
        platform,
        method,
        path,
        new AuthPlayIntegrityService.PlayIntegrityHeaders(
            exchange.getRequest().getHeaders().getFirst("X-App-Distribution-Channel"),
            exchange.getRequest().getHeaders().getFirst("X-Play-Integrity-Token"),
            exchange.getRequest().getHeaders().getFirst("X-Play-Integrity-Request-Hash")));
    authAppAttestService.verifyProtectedRequest(
        deviceId,
        platform,
        method,
        path,
        new AuthAppAttestService.AppAttestHeaders(
            exchange.getRequest().getHeaders().getFirst("X-App-Attest-Challenge-Id"),
            exchange.getRequest().getHeaders().getFirst("X-App-Attest-Key-Id"),
            exchange.getRequest().getHeaders().getFirst("X-App-Attest-Assertion"),
            exchange.getRequest().getHeaders().getFirst("X-App-Attest-Capability")));
    AuthDeviceProofService.VerifiedDeviceProof proof =
        authDeviceProofService.verifyProtectedRequest(
            deviceId,
            platform,
            method,
            path,
            new AuthDeviceProofService.DeviceProofHeaders(
                exchange.getRequest().getHeaders().getFirst("X-Device-Proof-Challenge-Id"),
                exchange.getRequest().getHeaders().getFirst("X-Device-Proof-Key-Id"),
                exchange.getRequest().getHeaders().getFirst("X-Device-Proof-Signature"),
                exchange.getRequest().getHeaders().getFirst("X-Device-Proof-Public-Key"),
                exchange.getRequest().getHeaders().getFirst("X-Device-Proof-Capability")));
    return proof == null ? null : proof.keyId();
  }

  private void requireSecureAndroidSocialAuthChannel(
      String platform,
      ServerWebExchange exchange,
      String operation) {
    authPlayIntegrityService.requireAllowedProtectedAuthChannel(
        platform,
        exchange.getRequest().getHeaders().getFirst("X-App-Distribution-Channel"),
        operation);
  }

  private String resolveClientIp(ServerWebExchange exchange) {
    String remoteIp = resolveRemoteIp(exchange);
    if (!trustsForwardedHeaders(remoteIp)) {
      return remoteIp;
    }

    String forwarded =
        AuthUtils.firstForwardedValue(exchange.getRequest().getHeaders().getFirst("X-Forwarded-For"));
    if (StringUtils.hasText(forwarded)) {
      return forwarded;
    }
    String realIp = AuthUtils.firstForwardedValue(exchange.getRequest().getHeaders().getFirst("X-Real-IP"));
    if (StringUtils.hasText(realIp)) {
      return realIp;
    }
    return remoteIp;
  }

  private String resolveExternalBaseUrl(ServerWebExchange exchange) {
    String configured = AuthUtils.normalizeBaseUrl(authProperties.getPublicBaseUrl());
    if (StringUtils.hasText(configured)) {
      return configured;
    }

    String remoteIp = resolveRemoteIp(exchange);
    boolean trustForwarded = trustsForwardedHeaders(remoteIp);

    String scheme =
        trustForwarded
            ? AuthUtils.normalizeScheme(exchange.getRequest().getHeaders().getFirst("X-Forwarded-Proto"))
            : "";
    String host =
        trustForwarded
            ? AuthUtils.normalizeForwardedHost(exchange.getRequest().getHeaders().getFirst("X-Forwarded-Host"))
            : "";
    if (!StringUtils.hasText(host) && trustForwarded) {
      host = AuthUtils.normalizeForwardedHost(exchange.getRequest().getHeaders().getFirst("Host"));
    }
    if (!StringUtils.hasText(scheme)) {
      scheme = AuthUtils.normalizeScheme(exchange.getRequest().getURI().getScheme());
    }
    if (!StringUtils.hasText(host)) {
      host = AuthUtils.normalizeForwardedHost(exchange.getRequest().getURI().getAuthority());
    }
    if (!StringUtils.hasText(host)) {
      host = "localhost";
    }
    if (!StringUtils.hasText(scheme)) {
      scheme = "https";
    }
    if ("http".equalsIgnoreCase(scheme) && !isLocalHost(host)) {
      scheme = "https";
    }
    return AuthUtils.buildBaseUrl(scheme, host);
  }

  private String resolveRemoteIp(ServerWebExchange exchange) {
    if (exchange.getRequest().getRemoteAddress() == null) return "";
    if (exchange.getRequest().getRemoteAddress().getAddress() == null) return "";
    return AuthUtils.normalizeIp(exchange.getRequest().getRemoteAddress().getAddress().getHostAddress());
  }

  private boolean trustsForwardedHeaders(String remoteIp) {
    return AuthUtils.isTrustedProxy(remoteIp, authProperties.getRisk().trustedProxyIpList());
  }

  private static boolean isLocalHost(String host) {
    if (host == null) return true;
    String h = host.trim().toLowerCase(Locale.ROOT);
    if (h.startsWith("localhost")) return true;
    if (h.startsWith("127.0.0.1")) return true;
    if (h.startsWith("0.0.0.0")) return true;
    if (h.endsWith(".local")) return true;
    return false;
  }

  private static String telegramWidgetHtml(String botUsername, String callbackUrl) {
    String bot = escapeHtmlAttr(botUsername);
    String cb = escapeHtmlAttr(callbackUrl);
    return """
        <!doctype html>
        <html lang="en">
          <head>
            <meta charset="utf-8" />
            <meta name="viewport" content="width=device-width, initial-scale=1" />
            <title>Telegram Login</title>
            <style>
              :root { color-scheme: light dark; }
              body { font-family: ui-sans-serif, system-ui, -apple-system, Segoe UI, Roboto, Arial, sans-serif; margin: 0; padding: 24px; }
              .wrap { max-width: 520px; margin: 0 auto; }
              h1 { font-size: 18px; margin: 0 0 12px; }
              p { opacity: 0.85; line-height: 1.5; margin: 0 0 16px; }
              .card { border: 1px solid rgba(127,127,127,0.35); border-radius: 14px; padding: 16px; }
              .hint { font-size: 12px; opacity: 0.7; margin-top: 12px; }
            </style>
          </head>
          <body>
            <div class="wrap">
              <h1>Continue with Telegram</h1>
              <div class="card">
                <p>Sign in with Telegram to continue.</p>
                <script async src="https://telegram.org/js/telegram-widget.js?22"
                  data-telegram-login="%s"
                  data-size="large"
                  data-userpic="false"
                  data-auth-url="%s"></script>
                <div class="hint">If you don't see the button, try opening in an external browser.</div>
              </div>
            </div>
          </body>
        </html>
        """
        .formatted(bot, cb);
  }

  private static String telegramLoginHtml(String clientId, String state, String nonce) {
    String cid = escapeHtmlAttr(clientId);
    String st = escapeHtmlAttr(state);
    String nn = escapeHtmlAttr(nonce);
    return """
        <!doctype html>
        <html lang="en">
          <head>
            <meta charset="utf-8" />
            <meta name="viewport" content="width=device-width, initial-scale=1" />
            <title>Telegram Login</title>
            <style>
              :root { color-scheme: light dark; }
              body { font-family: ui-sans-serif, system-ui, -apple-system, Segoe UI, Roboto, Arial, sans-serif; margin: 0; padding: 24px; }
              .wrap { max-width: 520px; margin: 0 auto; }
              h1 { font-size: 18px; margin: 0 0 12px; }
              p { opacity: 0.85; line-height: 1.5; margin: 0 0 16px; }
              .card { border: 1px solid rgba(127,127,127,0.35); border-radius: 14px; padding: 16px; }
              .tg-auth-button {
                appearance: none;
                border: none;
                border-radius: 999px;
                background: #119af5;
                color: white;
                font-weight: 600;
                font-size: 16px;
                min-height: 44px;
                padding: 0 28px;
                cursor: pointer;
              }
              .tg-auth-button:disabled { opacity: 0.72; cursor: default; }
              .status { min-height: 20px; font-size: 13px; margin: 12px 0 0; opacity: 0.82; }
              .status[data-kind="error"] { color: #d64545; opacity: 1; }
              .status[data-kind="warn"] { color: #b7791f; opacity: 1; }
              .hint { font-size: 12px; opacity: 0.7; margin-top: 12px; }
            </style>
          </head>
          <body>
            <div class="wrap">
              <h1>Continue with Telegram</h1>
              <div class="card">
                <p>Use the official Telegram login flow to continue.</p>
                <button id="tg-login-btn" class="tg-auth-button" type="button">Continue with Telegram</button>
                <div id="status" class="status" aria-live="polite"></div>
                <div class="hint">If Telegram does not open automatically, tap the button above.</div>
              </div>
            </div>
            <div id="cfg"
              data-client-id="%s"
              data-state="%s"
              data-nonce="%s"></div>
            <script src="https://oauth.telegram.org/js/telegram-login.js?3" async></script>
            <script>
              (function () {
                var cfgEl = document.getElementById('cfg');
                var button = document.getElementById('tg-login-btn');
                var status = document.getElementById('status');
                var opening = false;

                function setStatus(message, kind) {
                  status.textContent = message || '';
                  status.dataset.kind = kind || '';
                }

                function appendHiddenField(form, name, value) {
                  var input = document.createElement('input');
                  input.type = 'hidden';
                  input.name = name;
                  input.value = value || '';
                  form.appendChild(input);
                }

                function finalizeLogin(idToken) {
                  setStatus('Finalizing sign-in...');
                  button.disabled = true;
                  var form = document.createElement('form');
                  form.method = 'POST';
                  form.action = '/api/v1/auth/tg/login/complete';
                  appendHiddenField(form, 'state', cfgEl.dataset.state);
                  appendHiddenField(form, 'idToken', idToken);
                  document.body.appendChild(form);
                  form.submit();
                }

                function handleAuthResult(result) {
                  opening = false;
                  button.disabled = false;
                  if (!result || typeof result !== 'object') {
                    setStatus('Telegram sign-in failed. Tap the button to try again.', 'error');
                    return;
                  }
                  if (result.error) {
                    var errorCode = String(result.error).toLowerCase();
                    if (errorCode === 'popup_closed' || errorCode === 'cancelled' || errorCode === 'canceled') {
                      setStatus('Login canceled. Tap the button to try again.', 'warn');
                      return;
                    }
                    if (errorCode === 'origin_required' || errorCode === 'origin required') {
                      setStatus('Telegram requires a valid website origin for this login flow.', 'error');
                      return;
                    }
                    setStatus('Telegram sign-in failed. Tap the button to try again.', 'error');
                    return;
                  }
                  if (!result.id_token || typeof result.id_token !== 'string') {
                    setStatus('Telegram sign-in failed. Tap the button to try again.', 'error');
                    return;
                  }
                  finalizeLogin(result.id_token);
                }

                function loginApi() {
                  if (!window.Telegram || !window.Telegram.Login || typeof window.Telegram.Login.auth !== 'function') {
                    return null;
                  }
                  return window.Telegram.Login;
                }

                function withPatchedTelegramWindowOpen(run) {
                  var originalOpen = window.open;
                  window.open = function(url, name, features) {
                    try {
                      if (typeof url === 'string' && url.indexOf('https://oauth.telegram.org/auth?') === 0) {
                        var patched = new URL(url, window.location.href);
                        if (!patched.searchParams.get('origin')) {
                          patched.searchParams.set('origin', window.location.origin || '');
                        }
                        url = patched.toString();
                      }
                    } catch (e) {
                    }
                    return originalOpen.call(window, url, name, features);
                  };
                  try {
                    return run();
                  } finally {
                    window.open = originalOpen;
                  }
                }

                function beginLogin(event, retryCount) {
                  if (event && typeof event.preventDefault === 'function') {
                    event.preventDefault();
                  }
                  if (opening) return;
                  opening = true;
                  button.disabled = true;
                  setStatus('Opening Telegram...');
                  try {
                    var api = loginApi();
                    if (!api) {
                      opening = false;
                      button.disabled = false;
                      if ((retryCount || 0) < 10) {
                        setTimeout(function () {
                          beginLogin(null, (retryCount || 0) + 1);
                        }, 150);
                        return;
                      }
                      setStatus('Telegram login library is not available. Please try again.', 'error');
                      return;
                    }
                    var clientId = Number(cfgEl.dataset.clientId);
                    var options = { client_id: Number.isFinite(clientId) ? clientId : cfgEl.dataset.clientId };
                    if (cfgEl.dataset.nonce) {
                      options.nonce = cfgEl.dataset.nonce;
                    }
                    withPatchedTelegramWindowOpen(function () {
                      api.auth(options, handleAuthResult);
                    });
                  } catch (e) {
                    opening = false;
                    button.disabled = false;
                    setStatus('Unable to open Telegram login. Please tap again.', 'error');
                  }
                }

                button.addEventListener('click', beginLogin);
                window.addEventListener('load', function () {
                  setTimeout(beginLogin, 200);
                });
              })();
            </script>
          </body>
        </html>
        """
        .formatted(cid, st, nn);
  }

  private static String escapeHtmlAttr(String value) {
    if (value == null) return "";
    return value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("'", "&#39;");
  }
}
