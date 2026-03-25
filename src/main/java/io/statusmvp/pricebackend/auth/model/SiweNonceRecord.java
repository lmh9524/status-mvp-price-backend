package io.statusmvp.pricebackend.auth.model;

public record SiweNonceRecord(
    String nonce,
    String address,
    String domain,
    String uri,
    long issuedAt,
    long expiresAt) {}

