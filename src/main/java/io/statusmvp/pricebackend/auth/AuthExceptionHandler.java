package io.statusmvp.pricebackend.auth;

import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebInputException;

@RestControllerAdvice(basePackages = "io.statusmvp.pricebackend.auth")
public class AuthExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(AuthExceptionHandler.class);

  @ExceptionHandler(AuthException.class)
  public ResponseEntity<Map<String, Object>> onAuthError(AuthException e) {
    HttpHeaders headers = new HttpHeaders();
    if (e.getRetryAfterSeconds() != null && e.getRetryAfterSeconds() > 0) {
      headers.add(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()));
    }
    Map<String, Object> body =
        Map.of(
            "ok", false,
            "code", e.getCode().name(),
            "message", e.getMessage(),
            "retryAfterSeconds", e.getRetryAfterSeconds() == null ? 0 : e.getRetryAfterSeconds(),
            "details", e.getDetails(),
            "timestamp", Instant.now().toEpochMilli());
    return ResponseEntity.status(e.getHttpStatus()).headers(headers).body(body);
  }

  @ExceptionHandler({
    ConstraintViolationException.class,
    BindException.class,
    WebExchangeBindException.class,
    ServerWebInputException.class,
    IllegalArgumentException.class
  })
  public ResponseEntity<Map<String, Object>> onBadRequest(Exception e) {
    return ResponseEntity.badRequest()
        .body(
            Map.of(
                "ok", false,
                "code", AuthErrorCode.BAD_REQUEST.name(),
                "message", e.getMessage() == null ? "Invalid request" : e.getMessage(),
                "timestamp", Instant.now().toEpochMilli()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, Object>> onUnknown(Exception e) {
    log.error("auth internal error", e);
    return ResponseEntity.status(500)
        .body(
            Map.of(
                "ok", false,
                "code", "INTERNAL_ERROR",
                "message", "Internal server error",
                "timestamp", Instant.now().toEpochMilli()));
  }
}

