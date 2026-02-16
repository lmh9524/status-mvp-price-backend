package io.statusmvp.pricebackend.auth.model;

public record OAuthStateRecord(
    String state,
    String provider,
    String codeVerifier,
    String appRedirectUri,
    long createdAt,
    long expiresAt) {}

