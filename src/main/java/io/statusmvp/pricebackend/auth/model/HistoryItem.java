package io.statusmvp.pricebackend.auth.model;

public record HistoryItem(
    String url, String title, String iconUrl, long visitedAt, long updatedAt, Long deletedAt) {}

