package io.statusmvp.pricebackend.model;

import java.util.List;

public record CandleResponse(
    String market,
    String symbol,
    Integer chainId,
    String contractAddress,
    String interval,
    String currency,
    String source,
    long from,
    long to,
    List<PriceCandle> candles) {}
