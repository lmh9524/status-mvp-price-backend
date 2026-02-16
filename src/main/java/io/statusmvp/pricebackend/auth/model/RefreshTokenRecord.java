package io.statusmvp.pricebackend.auth.model;

public record RefreshTokenRecord(
    String id,
    String walletSub,
    String tokenHash,
    long createdAt,
    long expiresAt,
    Long revokedAt,
    String replacedBy) {}

