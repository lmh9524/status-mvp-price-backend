package io.statusmvp.pricebackend.model;

import java.util.List;

public record PortfolioSnapshot(
    String address,
    long fetchedAt,
    Double totalUsd,
    List<PortfolioChainSummary> chains) {}

