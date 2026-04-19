package io.statusmvp.pricebackend.auth.model;

public record DeviceProofChallengeRecord(
    String challengeId,
    String challenge,
    String deviceId,
    String method,
    String path,
    long createdAt,
    long expiresAt) {}
