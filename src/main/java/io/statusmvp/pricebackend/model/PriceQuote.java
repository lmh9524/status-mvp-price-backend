package io.statusmvp.pricebackend.model;

public record PriceQuote(
    String symbol,
    Double price,
    String currency,
    Long timestamp,
    String source,
    String contractAddress,
    Integer chainId) {}


