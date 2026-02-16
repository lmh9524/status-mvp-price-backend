package io.statusmvp.pricebackend.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.statusmvp.pricebackend.auth.model.AuthCodeRecord;
import io.statusmvp.pricebackend.auth.model.OAuthStateRecord;
import io.statusmvp.pricebackend.auth.model.RefreshTokenRecord;
import io.statusmvp.pricebackend.auth.model.WalletProfile;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class AuthRedisStore {
  private static final String PREFIX_OAUTH_STATE = "auth:oauth:state:";
  private static final String PREFIX_AUTH_CODE = "auth:code:";
  private static final String PREFIX_AUTH_CODE_USED = "auth:code:used:";
  private static final String PREFIX_PROVIDER_TO_WALLET = "auth:provider:";
  private static final String PREFIX_WALLET = "auth:wallet:";
  private static final String PREFIX_REFRESH = "auth:refresh:";
  private static final String PREFIX_JTI = "auth:jti:";

  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;

  public AuthRedisStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
    this.redis = redis;
    this.objectMapper = objectMapper;
  }

  public void putOAuthState(OAuthStateRecord record, long ttlSeconds) {
    putJson(PREFIX_OAUTH_STATE + record.state(), record, ttlSeconds);
  }

  public Optional<OAuthStateRecord> consumeOAuthState(String state) {
    if (state == null || state.isBlank()) return Optional.empty();
    String key = PREFIX_OAUTH_STATE + state;
    Optional<OAuthStateRecord> out = getJson(key, OAuthStateRecord.class);
    redis.delete(key);
    return out;
  }

  public void putAuthCode(AuthCodeRecord record, long ttlSeconds) {
    putJson(PREFIX_AUTH_CODE + record.code(), record, ttlSeconds);
  }

  public Optional<AuthCodeRecord> getAuthCode(String code) {
    if (code == null || code.isBlank()) return Optional.empty();
    return getJson(PREFIX_AUTH_CODE + code, AuthCodeRecord.class);
  }

  public boolean markAuthCodeUsedOnce(String code, long ttlSeconds) {
    if (code == null || code.isBlank()) return false;
    Boolean ok =
        redis
            .opsForValue()
            .setIfAbsent(
                PREFIX_AUTH_CODE_USED + code,
                "1",
                Duration.ofSeconds(Math.max(1, ttlSeconds)));
    return Boolean.TRUE.equals(ok);
  }

  public void updateAuthCode(AuthCodeRecord record) {
    String key = PREFIX_AUTH_CODE + record.code();
    Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
    if (ttl == null || ttl <= 0) {
      putJson(key, record, 1);
      return;
    }
    putJson(key, record, ttl);
  }

  public Optional<String> getWalletSubByProviderSub(String providerSub) {
    if (providerSub == null || providerSub.isBlank()) return Optional.empty();
    return Optional.ofNullable(redis.opsForValue().get(PREFIX_PROVIDER_TO_WALLET + providerSub));
  }

  public boolean bindProviderSubIfAbsent(String providerSub, String walletSub) {
    Boolean ok = redis.opsForValue().setIfAbsent(PREFIX_PROVIDER_TO_WALLET + providerSub, walletSub);
    return Boolean.TRUE.equals(ok);
  }

  public void bindProviderSubForce(String providerSub, String walletSub) {
    redis.opsForValue().set(PREFIX_PROVIDER_TO_WALLET + providerSub, walletSub);
  }

  public void unbindProviderSub(String providerSub) {
    redis.delete(PREFIX_PROVIDER_TO_WALLET + providerSub);
  }

  public Optional<WalletProfile> getWalletProfile(String walletSub) {
    if (walletSub == null || walletSub.isBlank()) return Optional.empty();
    return getJson(PREFIX_WALLET + walletSub, WalletProfile.class);
  }

  public void putWalletProfile(WalletProfile walletProfile) {
    putJson(PREFIX_WALLET + walletProfile.walletSub(), walletProfile, 0);
  }

  public void putRefreshToken(RefreshTokenRecord record, long ttlSeconds) {
    putJson(PREFIX_REFRESH + record.tokenHash(), record, ttlSeconds);
  }

  public Optional<RefreshTokenRecord> getRefreshTokenByHash(String tokenHash) {
    if (tokenHash == null || tokenHash.isBlank()) return Optional.empty();
    return getJson(PREFIX_REFRESH + tokenHash, RefreshTokenRecord.class);
  }

  public void consumeRefreshTokenByHash(String tokenHash) {
    redis.delete(PREFIX_REFRESH + tokenHash);
  }

  public boolean rememberJti(String jti, long ttlSeconds) {
    if (jti == null || jti.isBlank()) return false;
    Boolean ok = redis.opsForValue().setIfAbsent(PREFIX_JTI + jti, "1", Duration.ofSeconds(Math.max(1, ttlSeconds)));
    return Boolean.TRUE.equals(ok);
  }

  private <T> Optional<T> getJson(String key, Class<T> type) {
    try {
      String raw = redis.opsForValue().get(key);
      if (raw == null || raw.isBlank()) return Optional.empty();
      return Optional.of(objectMapper.readValue(raw, type));
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  private void putJson(String key, Object payload, long ttlSeconds) {
    try {
      String raw = objectMapper.writeValueAsString(payload);
      if (ttlSeconds > 0) {
        redis.opsForValue().set(key, raw, Duration.ofSeconds(Math.max(1, ttlSeconds)));
      } else {
        redis.opsForValue().set(key, raw);
      }
    } catch (JsonProcessingException e) {
      throw new AuthException(AuthErrorCode.BAD_REQUEST, "json serialization error", 400);
    }
  }
}
