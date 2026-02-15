package io.statusmvp.pricebackend.model;

import java.util.List;
import java.util.Map;

public record PortfolioSnapshotV2(
    String address,
    long fetchedAt,
    String currency,
    Double totalUsd,
    Map<Integer, Long> blockNumbersByChainId,
    List<PortfolioAssetSnapshotV2> assets) {}

