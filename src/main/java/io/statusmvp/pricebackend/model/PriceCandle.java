package io.statusmvp.pricebackend.model;

public record PriceCandle(
    long time,
    double open,
    double high,
    double low,
    double close,
    Double volume,
    Long trades) {}
