package io.statusmvp.pricebackend.update;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.update")
public class AppUpdateProperties {
  private Android android = new Android();

  public Android getAndroid() {
    return android;
  }

  public void setAndroid(Android android) {
    this.android = android;
  }

  public static class Android {
    private boolean enabled = false;
    private String channel = "official";
    private String packageName = "com.statusmvp";
    private String manifestUrl = "";
    private long manifestCacheTtlSeconds = 300L;

    private long latestVersionCode = 0L;
    private String latestVersionName = "";
    private long minSupportedVersionCode = 0L;
    private boolean required = false;

    private String downloadUrl = "";
    private String sha256 = "";
    private long fileSizeBytes = 0L;
    private String releaseNotes = "";
    private long publishedAt = 0L;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public String getChannel() {
      return channel;
    }

    public void setChannel(String channel) {
      this.channel = channel;
    }

    public String getPackageName() {
      return packageName;
    }

    public void setPackageName(String packageName) {
      this.packageName = packageName;
    }

    public String getManifestUrl() {
      return manifestUrl;
    }

    public void setManifestUrl(String manifestUrl) {
      this.manifestUrl = manifestUrl;
    }

    public long getManifestCacheTtlSeconds() {
      return manifestCacheTtlSeconds;
    }

    public void setManifestCacheTtlSeconds(long manifestCacheTtlSeconds) {
      this.manifestCacheTtlSeconds = manifestCacheTtlSeconds;
    }

    public long getLatestVersionCode() {
      return latestVersionCode;
    }

    public void setLatestVersionCode(long latestVersionCode) {
      this.latestVersionCode = latestVersionCode;
    }

    public String getLatestVersionName() {
      return latestVersionName;
    }

    public void setLatestVersionName(String latestVersionName) {
      this.latestVersionName = latestVersionName;
    }

    public long getMinSupportedVersionCode() {
      return minSupportedVersionCode;
    }

    public void setMinSupportedVersionCode(long minSupportedVersionCode) {
      this.minSupportedVersionCode = minSupportedVersionCode;
    }

    public boolean isRequired() {
      return required;
    }

    public void setRequired(boolean required) {
      this.required = required;
    }

    public String getDownloadUrl() {
      return downloadUrl;
    }

    public void setDownloadUrl(String downloadUrl) {
      this.downloadUrl = downloadUrl;
    }

    public String getSha256() {
      return sha256;
    }

    public void setSha256(String sha256) {
      this.sha256 = sha256;
    }

    public long getFileSizeBytes() {
      return fileSizeBytes;
    }

    public void setFileSizeBytes(long fileSizeBytes) {
      this.fileSizeBytes = fileSizeBytes;
    }

    public String getReleaseNotes() {
      return releaseNotes;
    }

    public void setReleaseNotes(String releaseNotes) {
      this.releaseNotes = releaseNotes;
    }

    public long getPublishedAt() {
      return publishedAt;
    }

    public void setPublishedAt(long publishedAt) {
      this.publishedAt = publishedAt;
    }
  }
}
