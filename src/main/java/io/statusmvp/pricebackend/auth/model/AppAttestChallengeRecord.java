package io.statusmvp.pricebackend.auth.model;

public record AppAttestChallengeRecord(
    String challengeId,
    String challenge,
    String deviceId,
    String keyId,
    long createdAt,
    long expiresAt) {}
