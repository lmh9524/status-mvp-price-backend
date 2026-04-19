package io.statusmvp.pricebackend.model;

public record PriceQuote(
    String symbol,
    Double price,
    Double change24hPct,
    String currency,
    Long timestamp,
    String source,
    String contractAddress,
    Integer chainId) {}

