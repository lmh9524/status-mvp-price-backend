package io.statusmvp.pricebackend.auth;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class AuthMetrics {
  private final MeterRegistry meterRegistry;
  private final AuthProperties authProperties;

  public AuthMetrics(MeterRegistry meterRegistry, AuthProperties authProperties) {
    this.meterRegistry = meterRegistry;
    this.authProperties = authProperties;
  }

  public void loginSuccess(String provider) {
    if (!authProperties.isMetricsEnabled()) return;
    meterRegistry.counter("auth.login.success", "provider", provider).increment();
  }

  public void loginFailure(String provider, String reason) {
    if (!authProperties.isMetricsEnabled()) return;
    meterRegistry.counter("auth.login.failure", "provider", provider, "reason", reason).increment();
  }

  public void providerUnavailable(String provider) {
    if (!authProperties.isMetricsEnabled()) return;
    meterRegistry.counter("auth.provider.unavailable", "provider", provider).increment();
  }

  public void bindSuccess() {
    if (!authProperties.isMetricsEnabled()) return;
    meterRegistry.counter("auth.bind.success").increment();
  }

  public void bindFailure(String reason) {
    if (!authProperties.isMetricsEnabled()) return;
    meterRegistry.counter("auth.bind.failure", "reason", reason).increment();
  }

  public void unbindSuccess() {
    if (!authProperties.isMetricsEnabled()) return;
    meterRegistry.counter("auth.unbind.success").increment();
  }

  public void unbindFailure(String reason) {
    if (!authProperties.isMetricsEnabled()) return;
    meterRegistry.counter("auth.unbind.failure", "reason", reason).increment();
  }

  public void syncError(String reason) {
    if (!authProperties.isMetricsEnabled()) return;
    meterRegistry.counter("auth.sync.error", "reason", reason).increment();
  }

  public void rateLimited(String scope) {
    if (!authProperties.isMetricsEnabled()) return;
    meterRegistry.counter("auth.rate_limited", "scope", scope).increment();
  }
}

