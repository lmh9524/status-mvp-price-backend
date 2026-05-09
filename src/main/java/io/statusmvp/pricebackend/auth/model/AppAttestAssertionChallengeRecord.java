package io.statusmvp.pricebackend.auth.model;

public record AppAttestAssertionChallengeRecord(
    String challengeId,
    String challenge,
    String deviceId,
    String keyId,
    String method,
    String path,
    long createdAt,
    long expiresAt) {}
