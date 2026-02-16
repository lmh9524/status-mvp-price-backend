package io.statusmvp.pricebackend.auth;

import java.util.Map;

public class AuthException extends RuntimeException {
  private final AuthErrorCode code;
  private final int httpStatus;
  private final Integer retryAfterSeconds;
  private final Map<String, Object> details;

  public AuthException(AuthErrorCode code, String message, int httpStatus) {
    this(code, message, httpStatus, null, Map.of());
  }

  public AuthException(AuthErrorCode code, String message, int httpStatus, Integer retryAfterSeconds) {
    this(code, message, httpStatus, retryAfterSeconds, Map.of());
  }

  public AuthException(
      AuthErrorCode code,
      String message,
      int httpStatus,
      Integer retryAfterSeconds,
      Map<String, Object> details) {
    super(message);
    this.code = code;
    this.httpStatus = httpStatus;
    this.retryAfterSeconds = retryAfterSeconds;
    this.details = details == null ? Map.of() : details;
  }

  public AuthErrorCode getCode() {
    return code;
  }

  public int getHttpStatus() {
    return httpStatus;
  }

  public Integer getRetryAfterSeconds() {
    return retryAfterSeconds;
  }

  public Map<String, Object> getDetails() {
    return details;
  }
}

