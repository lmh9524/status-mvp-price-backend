package io.statusmvp.pricebackend.model.token;

import java.util.List;

public record TokenSearchResponse(
    int chainId,
    String query,
    long updatedAt,
    boolean stale,
    List<TokenSearchItem> tokens) {}
