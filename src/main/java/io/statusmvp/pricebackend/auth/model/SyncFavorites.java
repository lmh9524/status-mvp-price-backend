package io.statusmvp.pricebackend.auth.model;

import java.util.ArrayList;
import java.util.List;

public record SyncFavorites(List<FavoriteItem> items, long updatedAt) {
  public SyncFavorites {
    items = items == null ? new ArrayList<>() : new ArrayList<>(items);
  }
}

