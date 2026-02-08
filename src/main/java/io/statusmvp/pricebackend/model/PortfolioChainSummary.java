package io.statusmvp.pricebackend.model;

public record PortfolioChainSummary(
    int chainId,
    String blockchain,
    String nativeSymbol,
    String nativeBalance,
    Double nativeUsdPrice,
    Double nativeUsdValue,
    Double tokenUsdValue,
    Double totalUsd,
    int tokenCount,
    int pricedTokenCount) {}

