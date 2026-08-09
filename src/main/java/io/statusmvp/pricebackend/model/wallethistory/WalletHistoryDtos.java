package io.statusmvp.pricebackend.model.wallethistory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public final class WalletHistoryDtos {
  private WalletHistoryDtos() {}

  public record QueryRequest(
      String address,
      List<Integer> chainIds,
      String cursor,
      Integer limit,
      String tokenAddress) {}

  public record QueryResponse(
      List<HistoryItem> items,
      String nextCursor,
      boolean hasMore,
      boolean partial,
      List<String> sources) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record HistoryItem(
      String id,
      int chainId,
      String hash,
      String from,
      String to,
      String value,
      String status,
      long createdAt,
      String kind,
      String tokenAddress,
      String tokenSymbol,
      Integer tokenDecimals,
      @JsonProperty("__external") boolean external,
      @JsonProperty("__source") String source) {}
}
