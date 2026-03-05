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

  public AuthController(AuthService authService) {
    this.authService = authService;
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

  @PostMapping(path = "/api/v1/auth/exchange", produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<AuthDtos.ExchangeResponse> exchange(@Valid @RequestBody AuthDtos.ExchangeRequest request) {
    return Mono.fromCallable(() -> authService.exchange(request))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @PostMapping(path = "/api/v1/auth/web3auth/jwt", produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<AuthDtos.Web3authJwtResponse> web3authJwt(
      @Valid @RequestBody AuthDtos.ExchangeRequest request) {
    return Mono.fromCallable(() -> authService.web3authJwt(request))
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
      @Valid @RequestBody AuthDtos.BindRequest request) {
    return Mono.fromCallable(() -> authService.bindProvider(authorizationHeader, request))
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

  private static String resolveClientIp(ServerWebExchange exchange) {
    String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      int idx = forwarded.indexOf(',');
      return idx > 0 ? forwarded.substring(0, idx).trim() : forwarded.trim();
    }
    String realIp = exchange.getRequest().getHeaders().getFirst("X-Real-IP");
    if (realIp != null && !realIp.isBlank()) return realIp.trim();
    if (exchange.getRequest().getRemoteAddress() == null) return "";
    if (exchange.getRequest().getRemoteAddress().getAddress() == null) return "";
    return exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
  }

  private static String resolveExternalBaseUrl(ServerWebExchange exchange) {
    String forwardedProto = exchange.getRequest().getHeaders().getFirst("X-Forwarded-Proto");
    String forwardedHost = exchange.getRequest().getHeaders().getFirst("X-Forwarded-Host");
    String host = forwardedHost;
    if (host != null) {
      int idx = host.indexOf(',');
      if (idx > 0) host = host.substring(0, idx).trim();
    }
    if (!StringUtils.hasText(host)) {
      host = exchange.getRequest().getHeaders().getFirst("Host");
    }

    String scheme = StringUtils.hasText(forwardedProto) ? forwardedProto.trim() : exchange.getRequest().getURI().getScheme();
    if (!StringUtils.hasText(scheme)) scheme = "https";
    if ("http".equalsIgnoreCase(scheme) && StringUtils.hasText(host) && !isLocalHost(host)) {
      // In production, TLS is typically terminated at the proxy; the app still needs an https callback URL.
      scheme = "https";
    }
    if (!StringUtils.hasText(host)) {
      // Best-effort fallback; should not happen in real deployments.
      return scheme + "://localhost";
    }
    return scheme + "://" + host.trim();
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
                  data-request-access="write"
                  data-auth-url="%s"></script>
                <div class="hint">If you don't see the button, try opening in an external browser.</div>
              </div>
            </div>
          </body>
        </html>
        """
        .formatted(bot, cb);
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
