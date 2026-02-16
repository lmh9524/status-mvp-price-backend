package io.statusmvp.pricebackend.auth.model;

public record FavoriteItem(
    String url, String name, String iconUrl, long updatedAt, Long deletedAt) {}

