package io.statusmvp.pricebackend.update;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public class AndroidUpdateManifestService {
  private static final Logger logger = LoggerFactory.getLogger(AndroidUpdateManifestService.class);
  private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-fA-F]{64}$");

  private final WebClient webClient;
  private final ObjectMapper objectMapper;
  private final AppUpdateProperties properties;
  private final Clock clock;

  private volatile CachedManifest cachedManifest;

  @Autowired
  public AndroidUpdateManifestService(
      WebClient webClient, ObjectMapper objectMapper, AppUpdateProperties properties) {
    this(webClient, objectMapper, properties, Clock.systemUTC());
  }

  AndroidUpdateManifestService(
      WebClient webClient, ObjectMapper objectMapper, AppUpdateProperties properties, Clock clock) {
    this.webClient = webClient;
    this.objectMapper = objectMapper;
    this.properties = properties;
    this.clock = clock;
  }

  public Mono<Optional<AndroidUpdateManifest>> getAndroidManifest() {
    AppUpdateProperties.Android cfg = properties.getAndroid();
    String manifestUrl = safeTrim(cfg.getManifestUrl());
    if (manifestUrl == null) {
      return Mono.just(buildInlineManifest(cfg));
    }

    long nowMs = clock.millis();
    CachedManifest snapshot = cachedManifest;
    long ttlMs = Math.max(0L, cfg.getManifestCacheTtlSeconds()) * 1000L;
    if (snapshot != null && ttlMs > 0 && nowMs - snapshot.fetchedAtMs < ttlMs) {
      return Mono.just(Optional.of(snapshot.manifest));
    }

    return webClient
        .get()
        .uri(manifestUrl)
        .accept(MediaType.APPLICATION_JSON)
        .retrieve()
        .bodyToMono(String.class)
        .map(this::parseAndValidateManifest)
        .doOnNext(manifest -> cachedManifest = new CachedManifest(manifest, nowMs))
        .map(Optional::of)
        .onErrorResume(
            error -> {
              CachedManifest fallback = cachedManifest;
              logger.warn("[AppUpdate] Failed to refresh Android manifest from {}: {}", manifestUrl, error.toString());
              if (fallback != null) {
                return Mono.just(Optional.of(fallback.manifest));
              }
              return Mono.just(Optional.empty());
            });
  }

  private Optional<AndroidUpdateManifest> buildInlineManifest(AppUpdateProperties.Android cfg) {
    try {
      return Optional.of(
          validateManifest(
              new AndroidUpdateManifest(
                  1,
                  cfg.getPackageName(),
                  cfg.getLatestVersionCode(),
                  cfg.getLatestVersionName(),
                  cfg.getMinSupportedVersionCode(),
                  cfg.isRequired(),
                  cfg.getDownloadUrl(),
                  cfg.getSha256(),
                  cfg.getFileSizeBytes(),
                  cfg.getReleaseNotes(),
                  cfg.getPublishedAt())));
    } catch (IllegalArgumentException error) {
      logger.warn("[AppUpdate] Inline Android update configuration is invalid: {}", error.getMessage());
      return Optional.empty();
    }
  }

  private AndroidUpdateManifest parseAndValidateManifest(String rawJson) {
    try {
      AndroidUpdateManifest manifest = objectMapper.readValue(rawJson, AndroidUpdateManifest.class);
      return validateManifest(manifest);
    } catch (IllegalArgumentException error) {
      throw error;
    } catch (Exception error) {
      throw new IllegalArgumentException("Invalid android-update.json payload", error);
    }
  }

  private AndroidUpdateManifest validateManifest(AndroidUpdateManifest manifest) {
    if (manifest == null) {
      throw new IllegalArgumentException("Manifest is missing");
    }
    if (manifest.schemaVersion() != 1) {
      throw new IllegalArgumentException("Unsupported schemaVersion");
    }

    String packageName = requireNonBlank(manifest.packageName(), "packageName");
    long latestVersionCode = requirePositive(manifest.latestVersionCode(), "latestVersionCode");
    String latestVersionName = requireNonBlank(manifest.latestVersionName(), "latestVersionName");
    long minSupportedVersionCode = requireNonNegative(manifest.minSupportedVersionCode(), "minSupportedVersionCode");
    long fileSizeBytes = requireNonNegative(manifest.fileSizeBytes(), "fileSizeBytes");
    long publishedAt = requireNonNegative(manifest.publishedAt(), "publishedAt");
    String downloadUrl = requireHttpsUrl(manifest.downloadUrl(), "downloadUrl");
    String sha256 = requireSha256(manifest.sha256());
    String releaseNotes = safeTrim(manifest.releaseNotes());

    return new AndroidUpdateManifest(
        1,
        packageName,
        latestVersionCode,
        latestVersionName,
        minSupportedVersionCode,
        manifest.required(),
        downloadUrl,
        sha256,
        fileSizeBytes,
        releaseNotes != null ? releaseNotes : "",
        publishedAt);
  }

  private static String requireNonBlank(String value, String fieldName) {
    String trimmed = safeTrim(value);
    if (trimmed == null) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
    return trimmed;
  }

  private static long requirePositive(long value, String fieldName) {
    if (value <= 0) {
      throw new IllegalArgumentException(fieldName + " must be > 0");
    }
    return value;
  }

  private static long requireNonNegative(long value, String fieldName) {
    if (value < 0) {
      throw new IllegalArgumentException(fieldName + " must be >= 0");
    }
    return value;
  }

  private static String requireHttpsUrl(String value, String fieldName) {
    String trimmed = requireNonBlank(value, fieldName);
    if (!trimmed.regionMatches(true, 0, "https://", 0, "https://".length())) {
      throw new IllegalArgumentException(fieldName + " must use https");
    }
    return trimmed;
  }

  private static String requireSha256(String value) {
    String trimmed = requireNonBlank(value, "sha256").toLowerCase();
    if (!SHA256_HEX.matcher(trimmed).matches()) {
      throw new IllegalArgumentException("sha256 must be 64 hex chars");
    }
    return trimmed;
  }

  private static String safeTrim(String value) {
    if (value == null) return null;
    String out = value.trim();
    return out.isEmpty() ? null : out;
  }

  private record CachedManifest(AndroidUpdateManifest manifest, long fetchedAtMs) {}
}
