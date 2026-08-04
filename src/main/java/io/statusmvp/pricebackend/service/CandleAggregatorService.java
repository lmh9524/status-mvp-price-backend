package io.statusmvp.pricebackend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.statusmvp.pricebackend.client.BinanceClient;
import io.statusmvp.pricebackend.client.CoinGeckoClient;
import io.statusmvp.pricebackend.model.CandleResponse;
import io.statusmvp.pricebackend.model.PriceCandle;
import io.statusmvp.pricebackend.util.PriceMappings;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Resolves OHLCV candles for the asset detail chart. Mirrors the fallback-chain approach used by
 * {@link PriceAggregatorService} for spot prices: try the cheapest/most-precise source first, fall
 * back to progressively more specialized sources for long-tail on-chain tokens.
 */
@Service
public class CandleAggregatorService {
  private static final Set<String> ALLOWED_INTERVALS = Set.of("1m", "5m", "15m", "1h", "4h", "1d");
  private static final Pattern SAFE_EXCHANGE_SYMBOL = Pattern.compile("^[A-Z0-9]{1,20}$");

  private final BinanceClient binance;
  private final CoinGeckoClient coinGecko;
  private final CoinGeckoIdResolver coinGeckoIds;
  private final RedisCache cache;
  private final ObjectMapper mapper = new ObjectMapper();

  private static final long POOL_ADDRESS_TTL_SECONDS = 21_600; // pools rarely change, 6h is plenty

  public CandleAggregatorService(
      BinanceClient binance, CoinGeckoClient coinGecko, CoinGeckoIdResolver coinGeckoIds, RedisCache cache) {
    this.binance = binance;
    this.coinGecko = coinGecko;
    this.coinGeckoIds = coinGeckoIds;
    this.cache = cache;
  }

  public CandleResponse getCandles(
      Integer chainId, String contractAddress, String symbol, String interval, int limit) {
    String iv = normalizeInterval(interval);
    int lim = Math.max(20, Math.min(500, limit));
    String lookup = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    String contract = contractAddress == null ? "" : contractAddress.trim().toLowerCase(Locale.ROOT);

    String requestKey =
        "candles:req:" + chainId + ":" + contract + ":" + lookup + ":" + iv + ":" + lim;
    Optional<String> cached = cache.get(requestKey);
    if (cached.isPresent()) {
      try {
        return mapper.readValue(cached.get(), CandleResponse.class);
      } catch (Exception ignored) {
        // fall through and refetch
      }
    }

    List<PriceCandle> candles = List.of();
    String source = null;

    if (!lookup.isBlank() && isSafeExchangeSymbol(lookup)) {
      candles = binance.fetchKlinesViaUsdtPair(lookup, iv, lim);
      if (!candles.isEmpty()) source = "binance";
    }

    if (candles.isEmpty() && !lookup.isBlank() && PriceMappings.STABLECOINS.contains(lookup)) {
      candles = syntheticStablecoinCandles(iv, lim);
      source = "stablecoin_flat";
    }

    if (candles.isEmpty() && chainId != null && !contract.isBlank()) {
      String networkId = PriceMappings.COINGECKO_ONCHAIN_NETWORKS.get(chainId);
      if (networkId != null && coinGecko.isEnabled()) {
        candles = fetchOnchainCandles(networkId, contract, iv, lim);
        if (!candles.isEmpty()) source = "geckoterminal";
      }
    }

    if (candles.isEmpty() && !lookup.isBlank() && coinGecko.isEnabled()) {
      String coinId = coinGeckoIds.resolve(lookup);
      if (coinId != null) {
        candles = coinGecko.fetchCoinOhlc(coinId, ohlcDaysForInterval(iv));
        if (candles.size() > lim) {
          candles = candles.subList(candles.size() - lim, candles.size());
        }
        if (!candles.isEmpty()) source = "coingecko_market_chart";
      }
    }

    long from = candles.isEmpty() ? 0L : candles.get(0).time();
    long to = candles.isEmpty() ? 0L : candles.get(candles.size() - 1).time();
    CandleResponse response =
        new CandleResponse(
            "onchain",
            symbol == null ? null : symbol.trim(),
            chainId,
            contractAddress,
            iv,
            "usd",
            source,
            from,
            to,
            candles);

    if (!candles.isEmpty()) {
      try {
        cache.set(requestKey, mapper.writeValueAsString(response), cacheTtlSecondsForInterval(iv));
      } catch (Exception ignored) {}
    }
    return response;
  }

  private List<PriceCandle> fetchOnchainCandles(String networkId, String contract, String interval, int limit) {
    String poolKey = "candles:pool:" + networkId + ":" + contract;
    String poolAddress =
        cache
            .get(poolKey)
            .orElseGet(
                () -> {
                  String resolved = coinGecko.fetchOnchainTopPoolAddress(networkId, contract).orElse("");
                  if (!resolved.isBlank()) {
                    cache.set(poolKey, resolved, POOL_ADDRESS_TTL_SECONDS);
                  }
                  return resolved;
                });
    if (poolAddress == null || poolAddress.isBlank()) return List.of();

    String timeframe;
    int aggregate;
    switch (interval) {
      case "1m" -> {
        timeframe = "minute";
        aggregate = 1;
      }
      case "5m" -> {
        timeframe = "minute";
        aggregate = 5;
      }
      case "15m" -> {
        timeframe = "minute";
        aggregate = 15;
      }
      case "4h" -> {
        timeframe = "hour";
        aggregate = 4;
      }
      case "1d" -> {
        timeframe = "day";
        aggregate = 1;
      }
      default -> {
        timeframe = "hour";
        aggregate = 1;
      }
    }
    return coinGecko.fetchOnchainPoolOhlcv(networkId, poolAddress, timeframe, aggregate, limit);
  }

  private static List<PriceCandle> syntheticStablecoinCandles(String interval, int limit) {
    long stepMs = intervalMillis(interval);
    long now = Instant.now().toEpochMilli();
    long lastBucket = (now / stepMs) * stepMs;
    List<PriceCandle> out = new ArrayList<>(limit);
    for (int i = limit - 1; i >= 0; i--) {
      long t = lastBucket - (long) i * stepMs;
      out.add(new PriceCandle(t, 1.0d, 1.0d, 1.0d, 1.0d, null, null));
    }
    return out;
  }

  private static long intervalMillis(String interval) {
    return switch (interval) {
      case "1m" -> 60_000L;
      case "5m" -> 300_000L;
      case "15m" -> 900_000L;
      case "4h" -> 14_400_000L;
      case "1d" -> 86_400_000L;
      default -> 3_600_000L; // 1h
    };
  }

  private static int ohlcDaysForInterval(String interval) {
    // CoinGecko's /coins/{id}/ohlc granularity is implied by `days`, not directly selectable:
    // 1-2 days -> 30m candles, 3-30 days -> 4h candles, 31+ days -> 4d candles.
    return switch (interval) {
      case "1d" -> 90;
      case "4h" -> 14;
      default -> 1;
    };
  }

  private static String normalizeInterval(String interval) {
    if (interval == null) return "1h";
    String v = interval.trim().toLowerCase(Locale.ROOT);
    return ALLOWED_INTERVALS.contains(v) ? v : "1h";
  }

  private static long cacheTtlSecondsForInterval(String interval) {
    return switch (interval) {
      case "1m", "5m" -> 30L;
      case "15m", "1h" -> 60L;
      default -> 300L; // 4h, 1d
    };
  }

  private static boolean isSafeExchangeSymbol(String symbol) {
    return SAFE_EXCHANGE_SYMBOL.matcher(symbol).matches();
  }
}
