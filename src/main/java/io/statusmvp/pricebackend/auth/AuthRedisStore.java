package io.statusmvp.pricebackend.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.statusmvp.pricebackend.auth.model.AppAttestAssertionChallengeRecord;
import io.statusmvp.pricebackend.auth.model.AppAttestChallengeRecord;
import io.statusmvp.pricebackend.auth.model.AppAttestRegistrationRecord;
import io.statusmvp.pricebackend.auth.model.AuthCodeRecord;
import io.statusmvp.pricebackend.auth.model.DeviceProofChallengeRecord;
import io.statusmvp.pricebackend.auth.model.DeviceProofRegistrationRecord;
import io.statusmvp.pricebackend.auth.model.OAuthStateRecord;
import io.statusmvp.pricebackend.auth.model.RefreshTokenRecord;
import io.statusmvp.pricebackend.auth.model.SiweNonceRecord;
import io.statusmvp.pricebackend.auth.model.WalletProfile;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AuthRedisStore {
  private static final String PREFIX_OAUTH_STATE = "auth:oauth:state:";
  private static final String PREFIX_OAUTH_STATE_DEVICE = "auth:oauth:state_device:";
  private static final String PREFIX_OAUTH_STATE_PROOF = "auth:oauth:state_proof:";
  private static final String PREFIX_AUTH_CODE = "auth:code:";
  private static final String PREFIX_AUTH_CODE_USED = "auth:code:used:";
  private static final String PREFIX_APP_ATTEST_CHALLENGE = "auth:app_attest:challenge:";
  private static final String PREFIX_APP_ATTEST_ASSERTION_CHALLENGE = "auth:app_attest:assertion:";
  private static final String PREFIX_APP_ATTEST_REGISTRATION = "auth:app_attest:device:";
  private static final String PREFIX_DEVICE_PROOF_CHALLENGE = "auth:device_proof:challenge:";
  private static final String PREFIX_DEVICE_PROOF_REGISTRATION = "auth:device_proof:device:";
  private static final String PREFIX_PROVIDER_TO_WALLET = "auth:provider:";
  private static final String PREFIX_WALLET = "auth:wallet:";
  private static final String PREFIX_WALLET_REFRESH = "auth:wallet_refresh:";
  private static final String PREFIX_WALLET_DELETED_AT = "auth:wallet_deleted_at:";
  private static final String PREFIX_REFRESH = "auth:refresh:";
  private static final String PREFIX_JTI = "auth:jti:";
  private static final String PREFIX_SIWE_NONCE = "auth:siwe:nonce:";
  private static final DefaultRedisScript<String> GET_AND_DELETE_SCRIPT =
      new DefaultRedisScript<>(
          "local value = redis.call('GET', KEYS[1]); "
              + "if value then redis.call('DEL', KEYS[1]); end; "
              + "return value",
          String.class);

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
    return getAndDeleteJson(key, OAuthStateRecord.class);
  }

  public Optional<OAuthStateRecord> peekOAuthState(String state) {
    if (state == null || state.isBlank()) return Optional.empty();
    return getJson(PREFIX_OAUTH_STATE + state, OAuthStateRecord.class);
  }

  public void putOAuthStateDevice(String state, String deviceId, long ttlSeconds) {
    if (state == null || state.isBlank()) return;
    if (deviceId == null || deviceId.isBlank()) return;
    redis
        .opsForValue()
        .set(
            PREFIX_OAUTH_STATE_DEVICE + state,
            deviceId.trim(),
            Duration.ofSeconds(Math.max(1, ttlSeconds)));
  }

  public Optional<String> consumeOAuthStateDevice(String state) {
    if (state == null || state.isBlank()) return Optional.empty();
    String key = PREFIX_OAUTH_STATE_DEVICE + state;
    String out = getAndDeleteRaw(key);
    if (out == null || out.isBlank()) return Optional.empty();
    return Optional.of(out.trim());
  }

  public void putOAuthStateProof(String state, String deviceProofKeyId, long ttlSeconds) {
    if (state == null || state.isBlank()) return;
    if (deviceProofKeyId == null || deviceProofKeyId.isBlank()) return;
    redis
        .opsForValue()
        .set(
            PREFIX_OAUTH_STATE_PROOF + state,
            deviceProofKeyId.trim(),
            Duration.ofSeconds(Math.max(1, ttlSeconds)));
  }

  public Optional<String> consumeOAuthStateProof(String state) {
    if (state == null || state.isBlank()) return Optional.empty();
    String key = PREFIX_OAUTH_STATE_PROOF + state;
    String out = getAndDeleteRaw(key);
    if (out == null || out.isBlank()) return Optional.empty();
    return Optional.of(out.trim());
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

  public void deleteWalletProfile(String walletSub) {
    if (walletSub == null || walletSub.isBlank()) return;
    redis.delete(PREFIX_WALLET + walletSub);
  }

  public void putRefreshToken(RefreshTokenRecord record, long ttlSeconds) {
    putJson(PREFIX_REFRESH + record.tokenHash(), record, ttlSeconds);
    if (StringUtils.hasText(record.walletSub()) && StringUtils.hasText(record.tokenHash())) {
      String setKey = PREFIX_WALLET_REFRESH + record.walletSub().trim();
      redis.opsForSet().add(setKey, record.tokenHash().trim());
      if (ttlSeconds > 0) {
        Long existingTtl = redis.getExpire(setKey, TimeUnit.SECONDS);
        if (existingTtl == null || existingTtl < ttlSeconds) {
          redis.expire(setKey, Duration.ofSeconds(Math.max(1, ttlSeconds)));
        }
      }
    }
  }

  public void putSiweNonce(SiweNonceRecord record, long ttlSeconds) {
    putJson(PREFIX_SIWE_NONCE + record.nonce(), record, ttlSeconds);
  }

  public Optional<SiweNonceRecord> consumeSiweNonce(String nonce) {
    if (nonce == null || nonce.isBlank()) return Optional.empty();
    String key = PREFIX_SIWE_NONCE + nonce;
    return getAndDeleteJson(key, SiweNonceRecord.class);
  }

  public Optional<RefreshTokenRecord> getRefreshTokenByHash(String tokenHash) {
    if (tokenHash == null || tokenHash.isBlank()) return Optional.empty();
    return getJson(PREFIX_REFRESH + tokenHash, RefreshTokenRecord.class);
  }

  public void consumeRefreshTokenByHash(String tokenHash) {
    if (tokenHash == null || tokenHash.isBlank()) return;
    Optional<RefreshTokenRecord> record = getRefreshTokenByHash(tokenHash);
    redis.delete(PREFIX_REFRESH + tokenHash);
    record.ifPresent(value -> removeRefreshTokenIndex(value.walletSub(), tokenHash));
  }

  public int revokeAllRefreshTokensForWallet(String walletSub) {
    if (walletSub == null || walletSub.isBlank()) return 0;
    String setKey = PREFIX_WALLET_REFRESH + walletSub.trim();
    Set<String> tokenHashes = redis.opsForSet().members(setKey);
    int revoked = 0;
    if (tokenHashes != null) {
      for (String tokenHash : tokenHashes) {
        if (!StringUtils.hasText(tokenHash)) continue;
        Boolean removed = redis.delete(PREFIX_REFRESH + tokenHash.trim());
        if (Boolean.TRUE.equals(removed)) {
          revoked++;
        }
      }
    }
    redis.delete(setKey);
    return revoked;
  }

  public void markWalletDeleted(String walletSub, long deletedAtMs, long ttlSeconds) {
    if (walletSub == null || walletSub.isBlank()) return;
    String key = PREFIX_WALLET_DELETED_AT + walletSub.trim();
    String value = String.valueOf(deletedAtMs);
    if (ttlSeconds > 0) {
      redis.opsForValue().set(key, value, Duration.ofSeconds(Math.max(1, ttlSeconds)));
    } else {
      redis.opsForValue().set(key, value);
    }
  }

  public Optional<Long> getWalletDeletedAt(String walletSub) {
    if (walletSub == null || walletSub.isBlank()) return Optional.empty();
    String raw = redis.opsForValue().get(PREFIX_WALLET_DELETED_AT + walletSub.trim());
    if (!StringUtils.hasText(raw)) return Optional.empty();
    try {
      return Optional.of(Long.parseLong(raw.trim()));
    } catch (NumberFormatException ignored) {
      return Optional.empty();
    }
  }

  public boolean rememberJti(String jti, long ttlSeconds) {
    if (jti == null || jti.isBlank()) return false;
    Boolean ok = redis.opsForValue().setIfAbsent(PREFIX_JTI + jti, "1", Duration.ofSeconds(Math.max(1, ttlSeconds)));
    return Boolean.TRUE.equals(ok);
  }

  public void revokeJti(String jti, long ttlSeconds) {
    if (jti == null || jti.isBlank()) return;
    redis.opsForValue().set(PREFIX_JTI + jti.trim(), "1", Duration.ofSeconds(Math.max(1, ttlSeconds)));
  }

  public boolean isJtiRevoked(String jti) {
    if (jti == null || jti.isBlank()) return false;
    return Boolean.TRUE.equals(redis.hasKey(PREFIX_JTI + jti.trim()));
  }

  public void putAppAttestChallenge(AppAttestChallengeRecord record, long ttlSeconds) {
    putJson(PREFIX_APP_ATTEST_CHALLENGE + record.challengeId(), record, ttlSeconds);
  }

  public Optional<AppAttestChallengeRecord> consumeAppAttestChallenge(String challengeId) {
    if (challengeId == null || challengeId.isBlank()) return Optional.empty();
    return getAndDeleteJson(PREFIX_APP_ATTEST_CHALLENGE + challengeId.trim(), AppAttestChallengeRecord.class);
  }

  public void putAppAttestAssertionChallenge(AppAttestAssertionChallengeRecord record, long ttlSeconds) {
    putJson(PREFIX_APP_ATTEST_ASSERTION_CHALLENGE + record.challengeId(), record, ttlSeconds);
  }

  public Optional<AppAttestAssertionChallengeRecord> consumeAppAttestAssertionChallenge(String challengeId) {
    if (challengeId == null || challengeId.isBlank()) return Optional.empty();
    return getAndDeleteJson(
        PREFIX_APP_ATTEST_ASSERTION_CHALLENGE + challengeId.trim(), AppAttestAssertionChallengeRecord.class);
  }

  public Optional<AppAttestRegistrationRecord> getAppAttestRegistration(String deviceId) {
    if (deviceId == null || deviceId.isBlank()) return Optional.empty();
    return getJson(PREFIX_APP_ATTEST_REGISTRATION + deviceId.trim(), AppAttestRegistrationRecord.class);
  }

  public void putAppAttestRegistration(AppAttestRegistrationRecord record) {
    if (record == null || !StringUtils.hasText(record.deviceId())) return;
    putJson(PREFIX_APP_ATTEST_REGISTRATION + record.deviceId().trim(), record, 0);
  }

  public void putDeviceProofChallenge(DeviceProofChallengeRecord record, long ttlSeconds) {
    putJson(PREFIX_DEVICE_PROOF_CHALLENGE + record.challengeId(), record, ttlSeconds);
  }

  public Optional<DeviceProofChallengeRecord> consumeDeviceProofChallenge(String challengeId) {
    if (challengeId == null || challengeId.isBlank()) return Optional.empty();
    return getAndDeleteJson(PREFIX_DEVICE_PROOF_CHALLENGE + challengeId.trim(), DeviceProofChallengeRecord.class);
  }

  public Optional<DeviceProofRegistrationRecord> getDeviceProofRegistration(String deviceId) {
    if (deviceId == null || deviceId.isBlank()) return Optional.empty();
    return getJson(PREFIX_DEVICE_PROOF_REGISTRATION + deviceId.trim(), DeviceProofRegistrationRecord.class);
  }

  public void putDeviceProofRegistration(DeviceProofRegistrationRecord record) {
    if (record == null || !StringUtils.hasText(record.deviceId())) return;
    putJson(PREFIX_DEVICE_PROOF_REGISTRATION + record.deviceId().trim(), record, 0);
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

  private <T> Optional<T> getAndDeleteJson(String key, Class<T> type) {
    try {
      String raw = getAndDeleteRaw(key);
      if (raw == null || raw.isBlank()) return Optional.empty();
      return Optional.of(objectMapper.readValue(raw, type));
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  private String getAndDeleteRaw(String key) {
    return redis.execute(GET_AND_DELETE_SCRIPT, List.of(key));
  }

  private void removeRefreshTokenIndex(String walletSub, String tokenHash) {
    if (!StringUtils.hasText(walletSub) || !StringUtils.hasText(tokenHash)) return;
    String setKey = PREFIX_WALLET_REFRESH + walletSub.trim();
    redis.opsForSet().remove(setKey, tokenHash.trim());
    Long size = redis.opsForSet().size(setKey);
    if (size != null && size <= 0) {
      redis.delete(setKey);
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
