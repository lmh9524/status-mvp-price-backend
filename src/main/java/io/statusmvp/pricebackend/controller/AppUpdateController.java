package io.statusmvp.pricebackend.controller;

import io.statusmvp.pricebackend.update.AppUpdateProperties;
import io.statusmvp.pricebackend.update.AndroidUpdateManifest;
import io.statusmvp.pricebackend.update.AndroidUpdateManifestService;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping(path = "/api/v1/app/android", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
public class AppUpdateController {
  private final AppUpdateProperties properties;
  private final AndroidUpdateManifestService manifestService;

  public AppUpdateController(
      AppUpdateProperties properties, AndroidUpdateManifestService manifestService) {
    this.properties = properties;
    this.manifestService = manifestService;
  }

  public record AndroidUpdateResponse(
      boolean hasUpdate,
      long latestVersionCode,
      String latestVersionName,
      long minSupportedVersionCode,
      boolean required,
      String downloadUrl,
      String sha256,
      long fileSizeBytes,
      String releaseNotes,
      long publishedAt) {}

  @GetMapping("/update")
  public Mono<AndroidUpdateResponse> getUpdate(
      @RequestParam("versionCode") @NotNull @Min(0) Long versionCode,
      @RequestParam(value = "packageName", required = false) String packageName,
      @RequestParam(value = "channel", required = false) String channel) {
    AppUpdateProperties.Android cfg = properties.getAndroid();

    String expectedPackage = safeTrim(cfg.getPackageName());
    String expectedChannel = safeTrim(cfg.getChannel());

    boolean enabled = cfg.isEnabled();
    if (enabled && expectedPackage != null && packageName != null && !expectedPackage.equals(packageName.trim())) {
      // Mismatch packageName: treat as no update for safety (prevents leaking other channel metadata).
      enabled = false;
    }
    if (enabled && expectedChannel != null && channel != null && !expectedChannel.equals(channel.trim())) {
      enabled = false;
    }
    if (!enabled) {
      return Mono.just(emptyResponse());
    }

    return manifestService
        .getAndroidManifest()
        .map(manifest -> toResponse(versionCode, expectedPackage, manifest.orElse(null)));
  }

  private AndroidUpdateResponse toResponse(
      long versionCode, String expectedPackage, AndroidUpdateManifest manifest) {
    if (manifest == null) {
      return emptyResponse();
    }

    String manifestPackage = safeTrim(manifest.packageName());
    if (expectedPackage != null && manifestPackage != null && !expectedPackage.equals(manifestPackage)) {
      return emptyResponse();
    }

    long latestCode = Math.max(0L, manifest.latestVersionCode());
    long minSupported = Math.max(0L, manifest.minSupportedVersionCode());
    boolean required = manifest.required() || (minSupported > 0 && versionCode < minSupported);

    String latestVersionName = safeTrim(manifest.latestVersionName());
    String downloadUrl = safeTrim(manifest.downloadUrl());
    String sha256 = safeTrim(manifest.sha256());
    long fileSizeBytes = Math.max(0L, manifest.fileSizeBytes());
    String releaseNotes = safeTrim(manifest.releaseNotes());
    long publishedAt = Math.max(0L, manifest.publishedAt());

    boolean hasUpdate =
        latestCode > 0
            && versionCode < latestCode
            && downloadUrl != null
            && !downloadUrl.isBlank()
            && sha256 != null
            && !sha256.isBlank();

    return new AndroidUpdateResponse(
        hasUpdate,
        latestCode,
        latestVersionName != null ? latestVersionName : "",
        minSupported,
        required && hasUpdate,
        downloadUrl != null ? downloadUrl : "",
        sha256 != null ? sha256 : "",
        fileSizeBytes,
        releaseNotes != null ? releaseNotes : "",
        publishedAt);
  }

  private AndroidUpdateResponse emptyResponse() {
    return new AndroidUpdateResponse(false, 0L, "", 0L, false, "", "", 0L, "", 0L);
  }

  private static String safeTrim(String value) {
    if (value == null) return null;
    String out = value.trim();
    return out.isEmpty() ? null : out;
  }
}
