package io.statusmvp.pricebackend.auth.model;

public record RefreshTokenRecord(
    String id,
    String walletSub,
    String tokenHash,
    String deviceId,
    String deviceProofKeyId,
    long createdAt,
    long expiresAt,
    Long revokedAt,
    String replacedBy) {}
