package io.statusmvp.pricebackend.auth.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import io.statusmvp.pricebackend.auth.model.FavoriteItem;
import io.statusmvp.pricebackend.auth.model.HistoryItem;
import io.statusmvp.pricebackend.auth.model.ProviderBinding;
import io.statusmvp.pricebackend.auth.model.SyncFavorites;
import io.statusmvp.pricebackend.auth.model.SyncHistory;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

public final class AuthDtos {
  private AuthDtos() {}

  public record OAuthStartResponse(String authorizeUrl, String state, long expiresInSeconds) {}

  public record XStartResponse(String authorizeUrl, String state, long expiresInSeconds) {}

  public record RedirectResponse(String redirectUrl) {}

  public record XResumeRequest(@NotBlank String resumeToken) {}

  public record AuthCodeResponse(
      String provider,
      String providerUserId,
      String providerSub,
      String code,
      long expiresInSeconds) {}

  public record AppleLoginRequest(@NotBlank String identityToken, @NotBlank String nonce) {}

  public record AppAttestChallengeRequest(String keyId) {}

  public record AppAttestChallengeResponse(
      boolean attestationRequired,
      String challengeId,
      String challenge,
      long expiresInSeconds) {}

  public record AppAttestRegisterRequest(
      @NotBlank String challengeId,
      @NotBlank String keyId,
      @NotBlank String attestationObjectBase64Url,
      String capability) {}

  public record AppAttestRegisterResponse(boolean registered, String keyId) {}

  public record AppAttestAssertionChallengeRequest(
      @NotBlank String keyId, @NotBlank String method, @NotBlank String path) {}

  public record AppAttestAssertionChallengeResponse(String challengeId, String challenge, long expiresInSeconds) {}

  public record DeviceProofChallengeRequest(@NotBlank String method, @NotBlank String path) {}

  public record DeviceProofChallengeResponse(String challengeId, String challenge, long expiresInSeconds) {}

  public record ExchangeRequest(@NotBlank String code, String nonce) {}

  public record ExchangeResponse(
      String walletSub,
      String provider,
      String providerUserId,
      String providerSub,
      String web3authJwt,
      String accessToken,
      String refreshToken,
      long accessTokenExpiresInSeconds,
      long refreshTokenExpiresInSeconds) {}

  public record RefreshRequest(@NotBlank String refreshToken) {}

  public record RefreshResponse(
      String accessToken,
      String refreshToken,
      long accessTokenExpiresInSeconds,
      long refreshTokenExpiresInSeconds) {}

  public record LogoutRequest(@NotBlank String refreshToken) {}

  public record LogoutResponse(boolean refreshTokenRevoked) {}

  public record MeResponse(String walletSub, List<ProviderBinding> providers) {}

  public record DeleteAccountRequest(String refreshToken) {}

  public record DeleteAccountResponse(String walletSub, int removedProviders, boolean refreshTokenRevoked) {}

  public record BindRequest(@NotBlank String authCode) {}

  public record UnbindRequest(@NotBlank String providerSub) {}

  public record TelegramLoginRequest(
      @NotBlank String id,
      @JsonAlias("first_name")
      String firstName,
      @JsonAlias("last_name")
      String lastName,
      String username,
      @JsonAlias("photo_url")
      String photoUrl,
      @JsonAlias("auth_date")
      @NotBlank String authDate,
      @NotBlank String hash,
      @JsonAlias("app_redirect_uri")
      String appRedirectUri) {}

  public record TelegramOidcCompleteRequest(
      @NotBlank String state,
      @JsonAlias("id_token")
      @NotBlank String idToken) {}

  public record SiweNonceRequest(@NotBlank String address) {}

  public record SiweNonceResponse(
      String nonce,
      String domain,
      String uri,
      String statement,
      long chainId,
      String issuedAt,
      String expirationTime) {}

  public record SiweVerifyRequest(@NotBlank String message, @NotBlank String signature) {}

  public record SiweVerifyResponse(
      String walletSub,
      String accessToken,
      String refreshToken,
      long accessTokenExpiresInSeconds,
      long refreshTokenExpiresInSeconds) {}

  public record SyncPayloadInput(
      List<FavoriteItem> favorites, Long favoritesUpdatedAt, List<HistoryItem> history, Long historyUpdatedAt) {}

  public record SyncPayloadResponse(SyncFavorites favorites, SyncHistory history) {}
}
