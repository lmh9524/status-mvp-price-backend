package io.statusmvp.pricebackend.auth.model;

public record SiweNonceRecord(
    String nonce,
    String address,
    String domain,
    String uri,
    String deviceProofKeyId,
    long issuedAt,
    long expiresAt) {}
