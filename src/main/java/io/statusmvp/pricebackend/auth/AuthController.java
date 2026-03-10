package io.statusmvp.pricebackend.auth;

import io.statusmvp.pricebackend.auth.dto.AuthDtos;
import jakarta.validation.Valid;
import java.util.Locale;
import org.springframework.util.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

  public AuthController(AuthService authService, AuthProperties authProperties) {
    this.authService = authService;
    this.authProperties = authProperties;
  }

  @GetMapping(path = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<ResponseEntity<java.util.Map<String, Object>>> jwks() {
    return Mono.fromCallable(() -> ResponseEntity.ok(authService.jwks()))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @GetMapping(path = "/api/v1/auth/x/start", produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<AuthDtos.XStartResponse> startX(
      @RequestParam(value = "appRedirectUri", required = false) String appRedirectUri,
      @RequestHeader(value = "X-Device-Id", required = false) String deviceId) {
    return Mono.fromCallable(() -> authService.startXLogin(appRedirectUri, deviceId))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @GetMapping(path = "/api/v1/auth/tg/start", produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<AuthDtos.OAuthStartResponse> startTelegram(
      @RequestParam(value = "appRedirectUri", required = false) String appRedirectUri,
      ServerWebExchange exchange,
      @RequestHeader(value = "X-Device-Id", required = false) String deviceId) {
    return Mono.fromCallable(
            () -> authService.startTelegramLogin(appRedirectUri, deviceId, resolveExternalBaseUrl(exchange)))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @GetMapping(path = "/api/v1/auth/tg/login", produces = MediaType.TEXT_HTML_VALUE)
  public Mono<ResponseEntity<String>> telegramLoginPage(
      @RequestParam(value = "state") String state,
      @RequestParam(value = "nonce") String nonce) {
    return Mono.fromCallable(
            () -> {
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
                AuthDtos.TelegramLoginRequest request =
                    new AuthDtos.TelegramLoginRequest(
                        id == null ? "" : id,
                        firstName,
                        lastName,
                        username,
                        photoUrl,
                        authDate == null ? "" : authDate,
                        hash == null ? "" : hash,
                        appRedirectUri);
                AuthDtos.AuthCodeResponse result = authService.telegramLogin(request, ip, deviceId);
                String location = authService.callbackRedirectUrl(appRedirectUri, result, state);
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
      ServerWebExchange exchange) {
    return Mono.fromCallable(() -> authService.resumeXLogin(request.resumeToken(), resolveClientIp(exchange), deviceId))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @PostMapping(path = "/api/v1/auth/tg/login", produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<AuthDtos.AuthCodeResponse> telegramLogin(
      @Valid @RequestBody AuthDtos.TelegramLoginRequest request,
      @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
      ServerWebExchange exchange) {
    return Mono.fromCallable(
            () -> authService.telegramLogin(request, resolveClientIp(exchange), deviceId))
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

  @PostMapping(path = "/api/v1/auth/exchange", produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<AuthDtos.ExchangeResponse> exchange(
      @Valid @RequestBody AuthDtos.ExchangeRequest request,
      @RequestHeader(value = "X-Device-Id", required = false) String deviceId) {
    return Mono.fromCallable(() -> authService.exchange(request, deviceId))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @PostMapping(path = "/api/v1/auth/web3auth/jwt", produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<AuthDtos.Web3authJwtResponse> web3authJwt(
      @Valid @RequestBody AuthDtos.ExchangeRequest request,
      @RequestHeader(value = "X-Device-Id", required = false) String deviceId) {
    return Mono.fromCallable(() -> authService.web3authJwt(request, deviceId))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @PostMapping(path = "/api/v1/auth/siwe/nonce", produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<AuthDtos.SiweNonceResponse> siweNonce(
      @Valid @RequestBody AuthDtos.SiweNonceRequest request,
      @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
      ServerWebExchange exchange) {
    return Mono.fromCallable(
            () ->
                authService.siweNonce(
                    request,
                    resolveClientIp(exchange),
                    deviceId,
                    resolveExternalBaseUrl(exchange)))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @PostMapping(path = "/api/v1/auth/siwe/verify", produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<AuthDtos.SiweVerifyResponse> siweVerify(
      @Valid @RequestBody AuthDtos.SiweVerifyRequest request,
      @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
      ServerWebExchange exchange) {
    return Mono.fromCallable(() -> authService.siweVerify(request, resolveClientIp(exchange), deviceId))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @PostMapping(path = "/api/v1/auth/refresh", produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<AuthDtos.RefreshResponse> refresh(@Valid @RequestBody AuthDtos.RefreshRequest request) {
    return Mono.fromCallable(() -> authService.refresh(request))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @GetMapping(path = "/api/v1/auth/me", produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<AuthDtos.MeResponse> me(@RequestHeader("Authorization") String authorizationHeader) {
    return Mono.fromCallable(() -> authService.me(authorizationHeader))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @PostMapping(path = "/api/v1/auth/providers/bind", produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<AuthDtos.MeResponse> bind(
      @RequestHeader("Authorization") String authorizationHeader,
      @Valid @RequestBody AuthDtos.BindRequest request,
      @RequestHeader(value = "X-Device-Id", required = false) String deviceId) {
    return Mono.fromCallable(() -> authService.bindProvider(authorizationHeader, request, deviceId))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @PostMapping(path = "/api/v1/auth/providers/unbind", produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<AuthDtos.MeResponse> unbind(
      @RequestHeader("Authorization") String authorizationHeader,
      @Valid @RequestBody AuthDtos.UnbindRequest request) {
    return Mono.fromCallable(() -> authService.unbindProvider(authorizationHeader, request))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @GetMapping(path = "/api/v1/auth/sync/dapps", produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<AuthDtos.SyncPayloadResponse> getSync(
      @RequestHeader("Authorization") String authorizationHeader) {
    return Mono.fromCallable(() -> authService.getSync(authorizationHeader))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @PostMapping(path = "/api/v1/auth/sync/dapps", produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<AuthDtos.SyncPayloadResponse> upsertSync(
      @RequestHeader("Authorization") String authorizationHeader,
      @Valid @RequestBody AuthDtos.SyncPayloadInput payload) {
    return Mono.fromCallable(() -> authService.upsertSync(authorizationHeader, payload))
        .subscribeOn(Schedulers.boundedElastic());
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
            <script src="https://oauth.telegram.org/js/telegram-login.js?3"></script>
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

                async function finalizeLogin(idToken) {
                  setStatus('Finalizing sign-in...');
                  button.disabled = true;
                  try {
                    var response = await fetch('/api/v1/auth/tg/login/complete', {
                      method: 'POST',
                      headers: { 'Content-Type': 'application/json' },
                      body: JSON.stringify({
                        state: cfgEl.dataset.state,
                        idToken: idToken
                      })
                    });
                    var payload = null;
                    try {
                      payload = await response.json();
                    } catch (e) {
                      payload = null;
                    }
                    if (!payload || !payload.redirectUrl) {
                      throw new Error('missing_redirect');
                    }
                    window.location.replace(payload.redirectUrl);
                  } catch (e) {
                    button.disabled = false;
                    setStatus('Telegram sign-in failed. Please try again.', 'error');
                  }
                }

                async function handleResult(result) {
                  opening = false;
                  if (!result) {
                    setStatus('Telegram sign-in failed. Please try again.', 'error');
                    return;
                  }
                  if (result.error) {
                    if (result.error === 'popup_closed') {
                      setStatus('Login canceled. Tap the button to try again.', 'warn');
                      return;
                    }
                    setStatus('Telegram sign-in failed. Tap the button to try again.', 'error');
                    return;
                  }
                  if (!result.id_token) {
                    setStatus('Telegram did not return a valid token.', 'error');
                    return;
                  }
                  await finalizeLogin(result.id_token);
                }

                function beginLogin() {
                  if (opening) return;
                  opening = true;
                  setStatus('Opening Telegram...');
                  try {
                    Telegram.Login.auth(
                      {
                        client_id: cfgEl.dataset.clientId,
                        nonce: cfgEl.dataset.nonce
                      },
                      handleResult
                    );
                  } catch (e) {
                    opening = false;
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
