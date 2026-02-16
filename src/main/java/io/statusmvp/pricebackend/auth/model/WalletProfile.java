package io.statusmvp.pricebackend.auth.model;

import java.util.LinkedHashMap;
import java.util.Map;

public record WalletProfile(
    String walletSub,
    long createdAt,
    Map<String, ProviderBinding> providers,
    SyncFavorites favorites,
    SyncHistory history) {
  public WalletProfile {
    providers = providers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(providers);
    favorites = favorites == null ? new SyncFavorites(null, 0L) : favorites;
    history = history == null ? new SyncHistory(null, 0L) : history;
  }

  public static WalletProfile create(String walletSub, long now) {
    return new WalletProfile(walletSub, now, new LinkedHashMap<>(), new SyncFavorites(null, now), new SyncHistory(null, now));
  }
}

