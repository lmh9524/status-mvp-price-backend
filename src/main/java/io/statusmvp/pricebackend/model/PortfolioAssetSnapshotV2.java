package io.statusmvp.pricebackend.model;

public record PortfolioAssetSnapshotV2(
    int chainId,
    String blockchain,
    boolean isNative,
    String contractAddress,
    String symbol,
    String name,
    Integer decimals,
    String balanceRaw,
    String balance,
    Double usdPrice,
    Double usdValue,
    String logoUrl,
    Long blockNumber) {}

