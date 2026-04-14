package io.statusmvp.pricebackend.controller;

import io.statusmvp.pricebackend.auth.AuthProperties;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AppLinksController {
  private final List<String> iosAppIds;
  private final String pathPrefix;
  private final String androidPackageName;
  private final List<String> androidSha256Fingerprints;

  public AppLinksController(
      @Value("${APP_LINKS_IOS_APP_IDS:4592CM9497.com.veilwallet.app}") String iosAppIds,
      @Value("${APP_LINKS_PATH_PREFIX:/openlogin}") String pathPrefix,
      @Value("${APP_LINKS_ANDROID_PACKAGE_NAME:com.statusmvp}") String androidPackageName,
      @Value("${APP_LINKS_ANDROID_SHA256_CERT_FINGERPRINTS:}") String androidSha256Fingerprints) {
    this.iosAppIds = AuthProperties.splitCsv(iosAppIds);
    this.pathPrefix = normalizePathPrefix(pathPrefix);
    this.androidPackageName = androidPackageName == null ? "" : androidPackageName.trim();
    this.androidSha256Fingerprints = AuthProperties.splitCsv(androidSha256Fingerprints);
  }

  @GetMapping(
      path = {"/.well-known/apple-app-site-association", "/apple-app-site-association"},
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Map<String, Object>> appleAppSiteAssociation() {
    List<Map<String, Object>> details =
        iosAppIds.stream()
            .map(
                appId ->
                    Map.<String, Object>of(
                        "appID",
                        appId,
                        "paths",
                        List.of(pathPrefix + "/*")))
            .toList();

    Map<String, Object> body =
        Map.of(
            "applinks",
            Map.of(
                "apps", List.of(),
                "details", details));

    return jsonResponse(body);
  }

  @GetMapping(path = "/.well-known/assetlinks.json", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<List<Map<String, Object>>> assetLinks() {
    if (androidPackageName.isBlank() || androidSha256Fingerprints.isEmpty()) {
      return jsonResponse(List.of());
    }

    Map<String, Object> target = new LinkedHashMap<>();
    target.put("namespace", "android_app");
    target.put("package_name", androidPackageName);
    target.put("sha256_cert_fingerprints", androidSha256Fingerprints);

    Map<String, Object> statement = new LinkedHashMap<>();
    statement.put("relation", List.of("delegate_permission/common.handle_all_urls"));
    statement.put("target", target);

    return jsonResponse(List.of(statement));
  }

  private static String normalizePathPrefix(String value) {
    String trimmed = value == null ? "" : value.trim();
    if (trimmed.isEmpty()) {
      return "/openlogin";
    }
    return trimmed.startsWith("/") ? trimmed.replaceAll("/+$", "") : "/" + trimmed.replaceAll("/+$", "");
  }

  private static <T> ResponseEntity<T> jsonResponse(T body) {
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_JSON)
        .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
        .body(body);
  }
}
