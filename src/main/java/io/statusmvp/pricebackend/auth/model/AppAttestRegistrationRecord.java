package io.statusmvp.pricebackend.auth.model;

public record AppAttestRegistrationRecord(
    String deviceId,
    String keyId,
    String applicationIdentifier,
    String publicKeySpkiBase64Url,
    String credentialIdBase64Url,
    String receiptBase64Url,
    String capability,
    long createdAt,
    long lastVerifiedAt,
    long assertionCounter) {}
