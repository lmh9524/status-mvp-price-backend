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

  public record XStartResponse(String authorizeUrl, String state, long expiresInSeconds) {}

  public record AuthCodeResponse(
      String provider,
      String providerUserId,
      String providerSub,
      String code,
      long expiresInSeconds) {}

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

  public record MeResponse(String walletSub, List<ProviderBinding> providers) {}

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

  public record SyncPayloadInput(
      List<FavoriteItem> favorites, Long favoritesUpdatedAt, List<HistoryItem> history, Long historyUpdatedAt) {}

  public record SyncPayloadResponse(SyncFavorites favorites, SyncHistory history) {}
}
