package io.statusmvp.pricebackend.auth;

import io.statusmvp.pricebackend.auth.dto.AuthDtos;
import jakarta.validation.Valid;
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
  public Mono<AuthDtos.XStartResponse> startX(@RequestParam(value = "appRedirectUri", required = false) String appRedirectUri) {
    return Mono.fromCallable(() -> authService.startXLogin(appRedirectUri))
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
              AuthService.XCallbackResult result =
                  authService.handleXCallback(code, state, error, errorDescription, ip, deviceId);
              if (result.appRedirectUri() != null && !result.appRedirectUri().isBlank()) {
                String location = authService.callbackRedirectUrl(result.appRedirectUri(), result.payload());
                return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, location).build();
              }
              return ResponseEntity.ok(result.payload());
            })
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
}
