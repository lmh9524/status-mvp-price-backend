package io.statusmvp.pricebackend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class PriceHistoryService {
  private static final Logger log = LoggerFactory.getLogger(PriceHistoryService.class);
  private static final long DAY_MS = Duration.ofHours(24).toMillis();

  private final StringRedisTemplate redis;
  private final ObjectMapper mapper = new ObjectMapper();
  private final long bucketMillis;
  private final int searchWindowBuckets;
  private final long sampleTtlSeconds;

  public PriceHistoryService(
      StringRedisTemplate redis,
      @Value("${app.marketHistory.bucketMinutes:30}") long bucketMinutes,
      @Value("${app.marketHistory.searchWindowBuckets:4}") int searchWindowBuckets,
      @Value("${app.marketHistory.sampleTtlSeconds:259200}") long sampleTtlSeconds) {
    this.redis = redis;
    long normalizedBucketMinutes = bucketMinutes <= 0 ? 30 : bucketMinutes;
    this.bucketMillis = Duration.ofMinutes(normalizedBucketMinutes).toMillis();
    this.searchWindowBuckets = Math.max(0, searchWindowBuckets);
    this.sampleTtlSeconds = Math.max(3600, sampleTtlSeconds);
  }

  public Double resolveChange24hPct(
      String assetKey, Double currentPrice, Double upstreamChange24hPct, long timestamp) {
    Double price = positiveOrNull(currentPrice);
    if (assetKey == null || assetKey.isBlank() || price == null) {
      return finiteOrNull(upstreamChange24hPct);
    }

    recordSample(assetKey, price, timestamp);

    Double upstream = finiteOrNull(upstreamChange24hPct);
    if (upstream != null) {
      return upstream;
    }

    PriceSample baseline = findBaselineSample(assetKey, timestamp);
    if (baseline == null || baseline.price() == null || baseline.price() <= 0d) {
      if (log.isDebugEnabled()) {
        log.debug("market history baseline not ready yet: assetKey={} timestamp={}", assetKey, timestamp);
      }
      return null;
    }

    double changePct = ((price - baseline.price()) / baseline.price()) * 100d;
    if (!Double.isFinite(changePct)) {
      log.warn(
          "market history produced non-finite 24h change: assetKey={} currentPrice={} baselinePrice={}",
          assetKey,
          price,
          baseline.price());
      return null;
    }
    return changePct;
  }

  public static String symbolAssetKey(String normalizedSymbol) {
    String symbol = normalizedSymbol == null ? "" : normalizedSymbol.trim().toUpperCase(Locale.ROOT);
    if (symbol.isBlank()) return "";
    return "symbol:" + symbol;
  }

  public static String contractAssetKey(int chainId, String normalizedAddress) {
    String address = normalizedAddress == null ? "" : normalizedAddress.trim();
    if (address.isBlank()) return "";
    return "contract:" + chainId + ":" + address;
  }

  private void recordSample(String assetKey, Double price, long timestamp) {
    String key = sampleKey(assetKey, bucketOf(timestamp));
    PriceSample sample = new PriceSample(price, timestamp);
    try {
      redis
          .opsForValue()
          .set(key, mapper.writeValueAsString(sample), Duration.ofSeconds(sampleTtlSeconds));
    } catch (Exception e) {
      log.warn("market history sample write failed: assetKey={} key={}", assetKey, key, e);
    }
  }

  private PriceSample findBaselineSample(String assetKey, long now) {
    long targetBucket = bucketOf(now - DAY_MS);
    for (long candidateBucket : candidateBuckets(targetBucket)) {
      String key = sampleKey(assetKey, candidateBucket);
      try {
        String raw = redis.opsForValue().get(key);
        if (raw == null || raw.isBlank()) continue;
        PriceSample sample = mapper.readValue(raw, PriceSample.class);
        Double baselinePrice = positiveOrNull(sample.price());
        if (baselinePrice == null) continue;
        return new PriceSample(baselinePrice, sample.timestamp());
      } catch (Exception e) {
        log.warn("market history sample read failed: assetKey={} key={}", assetKey, key, e);
      }
    }
    return null;
  }

  private List<Long> candidateBuckets(long targetBucket) {
    List<Long> out = new ArrayList<>();
    out.add(targetBucket);
    for (int offset = 1; offset <= searchWindowBuckets; offset++) {
      out.add(targetBucket - offset);
      out.add(targetBucket + offset);
    }
    return out;
  }

  private long bucketOf(long timestamp) {
    return Math.max(0L, timestamp / bucketMillis);
  }

  private String sampleKey(String assetKey, long bucket) {
    return "market-history:sample:" + assetKey + ":" + bucket;
  }

  private static Double positiveOrNull(Double value) {
    if (value == null || !Double.isFinite(value) || value <= 0d) return null;
    return value;
  }

  private static Double finiteOrNull(Double value) {
    if (value == null || !Double.isFinite(value)) return null;
    return value;
  }

  private record PriceSample(Double price, Long timestamp) {}
}
