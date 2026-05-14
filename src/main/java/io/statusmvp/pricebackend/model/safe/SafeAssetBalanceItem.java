package io.statusmvp.pricebackend.model.safe;

import java.util.List;

public record SafeAssetBalanceItem(
    String tokenAddress,
    SafeAssetBalanceToken token,
    String balance,
    Boolean trusted,
    List<String> sources) {}
