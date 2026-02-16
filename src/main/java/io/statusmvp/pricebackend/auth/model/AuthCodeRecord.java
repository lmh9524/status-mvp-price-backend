package io.statusmvp.pricebackend.auth.model;

public record AuthCodeRecord(
    String code,
    String provider,
    String providerUserId,
    String providerSub,
    long createdAt,
    long expiresAt,
    Long usedAt) {}

