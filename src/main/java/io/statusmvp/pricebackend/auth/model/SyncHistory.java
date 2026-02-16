package io.statusmvp.pricebackend.auth.model;

import java.util.ArrayList;
import java.util.List;

public record SyncHistory(List<HistoryItem> items, long updatedAt) {
  public SyncHistory {
    items = items == null ? new ArrayList<>() : new ArrayList<>(items);
  }
}

