package io.statusmvp.pricebackend.model.token;

import java.util.List;

public record TokenSearchItem(
    int chainId,
    String address,
    String standard,
    String symbol,
    String name,
    int decimals,
    String logoURI,
    List<String> sources,
    String confidence) {}
