package io.statusmvp.pricebackend.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.statusmvp.pricebackend.auth.dto.AuthDtos;
import io.statusmvp.pricebackend.auth.model.ProviderBinding;
import io.statusmvp.pricebackend.auth.model.RefreshTokenRecord;
import io.statusmvp.pricebackend.auth.model.WalletProfile;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthServiceDeleteAccountTest {
  private AuthProperties authProperties;
  private AuthRedisStore store;
  private AuthJwtService jwtService;
  private AuthMetrics metrics;
  private AuthService service;

  @BeforeEach
  void setUp() {
    authProperties = mock(AuthProperties.class);
    store = mock(AuthRedisStore.class);
    XOAuthClient xOAuthClient = mock(XOAuthClient.class);
    TelegramOidcClient telegramOidcClient = mock(TelegramOidcClient.class);
    AppleIdentityTokenClient appleIdentityTokenClient = mock(AppleIdentityTokenClient.class);
    TelegramVerifier telegramVerifier = mock(TelegramVerifier.class);
    AuthRiskService riskService = mock(AuthRiskService.class);
    jwtService = mock(AuthJwtService.class);
    metrics = mock(AuthMetrics.class);

    when(authProperties.isEnabled()).thenReturn(true);
    when(authProperties.getAccessTokenTtlSeconds()).thenReturn(3600L);

    service =
        new AuthService(
            authProperties,
            store,
            xOAuthClient,
            telegramOidcClient,
            appleIdentityTokenClient,
            telegramVerifier,
            riskService,
            jwtService,
            metrics,
            new ObjectMapper());
  }

  @Test
  void deleteAccountRemovesBindingsRevokesRefreshTokensAndMarksTombstone() {
    String walletSub = "wallet_123";
    String refreshToken = "refresh-token";
    String refreshHash = "refresh-hash";
    String providerSubX = "x:abc";
    String providerSubApple = "apple:def";

    when(jwtService.verifyAccessToken("access-token"))
        .thenReturn(new AuthJwtService.AccessTokenClaims(walletSub, "jti-1", 10_000L, 20_000L));
    when(store.getWalletDeletedAt(walletSub)).thenReturn(Optional.empty());
    when(jwtService.sha256Hex(refreshToken)).thenReturn(refreshHash);
    when(store.getRefreshTokenByHash(refreshHash))
        .thenReturn(Optional.of(new RefreshTokenRecord("id-1", walletSub, refreshHash, 1L, 2L, null, null)));
    when(store.revokeAllRefreshTokensForWallet(walletSub)).thenReturn(2);

    Map<String, ProviderBinding> providers = new LinkedHashMap<>();
    providers.put(providerSubX, new ProviderBinding("x", "user-x", providerSubX, 1L));
    providers.put(providerSubApple, new ProviderBinding("apple", "user-apple", providerSubApple, 2L));
    when(store.getWalletProfile(walletSub))
        .thenReturn(
            Optional.of(
                new WalletProfile(
                    walletSub,
                    1L,
                    providers,
                    WalletProfile.create(walletSub, 1L).favorites(),
                    WalletProfile.create(walletSub, 1L).history())));

    AuthDtos.DeleteAccountResponse response =
        service.deleteAccount("Bearer access-token", new AuthDtos.DeleteAccountRequest(refreshToken));

    assertEquals(walletSub, response.walletSub());
    assertEquals(2, response.removedProviders());
    assertTrue(response.refreshTokenRevoked());

    verify(store).unbindProviderSub(providerSubX);
    verify(store).unbindProviderSub(providerSubApple);
    verify(store).markWalletDeleted(eq(walletSub), org.mockito.ArgumentMatchers.longThat(v -> v > 0), anyLong());
    verify(store).revokeAllRefreshTokensForWallet(walletSub);
    verify(store).deleteWalletProfile(walletSub);
    verify(metrics).deleteSuccess();
  }

  @Test
  void meRejectsDeletedAccountToken() {
    String walletSub = "wallet_deleted";
    when(jwtService.verifyAccessToken("access-token"))
        .thenReturn(new AuthJwtService.AccessTokenClaims(walletSub, "jti-1", 10_000L, 20_000L));
    when(store.getWalletDeletedAt(walletSub)).thenReturn(Optional.of(15_000L));

    AuthException error = assertThrows(AuthException.class, () -> service.me("Bearer access-token"));
    assertEquals(AuthErrorCode.ACCOUNT_DELETED, error.getCode());
    verify(store, never()).getWalletProfile(walletSub);
  }
}
