package io.statusmvp.pricebackend.auth.model;

public record DeviceProofRegistrationRecord(
    String deviceId,
    String keyId,
    String publicKeyBase64Url,
    String platform,
    long createdAt,
    long lastVerifiedAt) {}
