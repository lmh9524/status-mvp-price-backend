package io.statusmvp.pricebackend.update;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AndroidUpdateManifest(
    int schemaVersion,
    String packageName,
    long latestVersionCode,
    String latestVersionName,
    long minSupportedVersionCode,
    boolean required,
    String downloadUrl,
    String sha256,
    long fileSizeBytes,
    String releaseNotes,
    long publishedAt) {}
