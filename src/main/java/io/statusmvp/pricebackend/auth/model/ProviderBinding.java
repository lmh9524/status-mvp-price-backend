package io.statusmvp.pricebackend.auth.model;

public record ProviderBinding(
    String provider, String providerUserId, String providerSub, long addedAt) {}

