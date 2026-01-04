package io.statusmvp.pricebackend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.statusmvp.pricebackend.client.BinanceClient;
import io.statusmvp.pricebackend.client.CoinGeckoClient;
import io.statusmvp.pricebackend.client.CoinMarketCapClient;
import io.statusmvp.pricebackend.model.PriceQuote;
import io.statusmvp.pricebackend.util.PriceMappings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class PriceAggregatorService {
  private final CoinGeckoClient coinGecko;
  private final CoinMarketCapClient cmc;
  private final BinanceClient binance;
  private final RedisCache cache;
  private final CoinGeckoIdResolver coinGeckoIds;
  private final VeilxDexPriceService veilxDex;
  private final ObjectMapper mapper = new ObjectMapper();

  private final long priceTtlSeconds;
  private final long requestTtlSeconds;

  public PriceAggregatorService(
      CoinGeckoClient coinGecko,
      CoinMarketCapClient cmc,
      BinanceClient binance,
      RedisCache cache,
      CoinGeckoIdResolver coinGeckoIds,
      VeilxDexPriceService veilxDex,
      @Value("${app.cache.priceTtlSeconds:120}") long priceTtlSeconds,
      @Value("${app.cache.requestTtlSeconds:30}") long requestTtlSeconds) {
    this.coinGecko = coinGecko;
    this.cmc = cmc;
    this.binance = binance;
    this.cache = cache;
    this.coinGeckoIds = coinGeckoIds;
    this.veilxDex = veilxDex;
    this.priceTtlSeconds = priceTtlSeconds;
    this.requestTtlSeconds = requestTtlSeconds;
  }

  public List<PriceQuote> getPrices(List<String> symbols, String currency) {
    String cur = normalizeCurrency(currency);
    List<String> normSymbols =
        symbols.stream()
            .map(s -> s == null ? "" : s.trim().toUpperCase(Locale.ROOT))
            .filter(s -> !s.isBlank())
            .distinct()
            .sorted()
            .toList();

    String requestKey = "req:prices:" + cur + ":" + sha1(String.join(",", normSymbols));
    Optional<String> cached = cache.get(requestKey);
    if (cached.isPresent()) {
      try {
        return mapper.readValue(cached.get(), new TypeReference<List<PriceQuote>>() {});
      } catch (Exception ignored) {
        // fall through
      }
    }

    long ts = Instant.now().toEpochMilli();
    List<PriceQuote> out = new ArrayList<>();
    for (String symbol : normSymbols) {
      out.add(getSingleSymbolPrice(symbol, cur, ts));
    }

    try {
      cache.set(requestKey, mapper.writeValueAsString(out), requestTtlSeconds);
    } catch (Exception ignored) {}

    return out;
  }

  private PriceQuote getSingleSymbolPrice(String symbol, String currency, long ts) {
    // Per-symbol cache
    String key = "price:symbol:" + symbol + ":" + currency;
    Optional<String> cached = cache.get(key);
    if (cached.isPresent()) {
      try {
        return mapper.readValue(cached.get(), PriceQuote.class);
      } catch (Exception ignored) {}
    }

    // Stablecoin fallback
    if ("usd".equals(currency) && PriceMappings.STABLECOINS.contains(symbol)) {
      PriceQuote q = new PriceQuote(symbol, 1.0, "usd", ts, "stablecoin_fallback", null, null);
      try {
        cache.set(key, mapper.writeValueAsString(q), priceTtlSeconds);
      } catch (Exception ignored) {}
      return q;
    }

    // VEILX on-chain DEX pricing (BSC PancakeSwap V2): 1 VEILX ~ X USDT (assume USDT~USD)
    if ("usd".equals(currency) && "VEILX".equals(symbol) && veilxDex != null && veilxDex.isEnabled()) {
      Double p = veilxDex.fetchVeilxUsdPrice().orElse(null);
      if (p != null) {
        PriceQuote q = new PriceQuote(symbol, p, "usd", ts, "pancakeswap_v2", null, null);
        try {
          cache.set(key, mapper.writeValueAsString(q), priceTtlSeconds);
        } catch (Exception ignored) {}
        return q;
      }
    }

    // 1) CoinGecko Pro (symbol -> id)
    Double price = null;
    String source = null;
    if (coinGecko.isEnabled()) {
      String id = coinGeckoIds.resolve(symbol);
      if (id != null) {
        price = coinGecko.fetchSimplePriceUsd(id).orElse(null);
        if (price != null) source = "coingecko";
      }
    }

    // 2) CoinMarketCap
    if (price == null && cmc.isEnabled()) {
      price = cmc.fetchUsdPriceBySymbol(symbol).orElse(null);
      if (price != null) source = "coinmarketcap";
    }

    // 3) Binance (USDT pair)
    if (price == null) {
      price = binance.fetchUsdPriceViaUsdtPair(symbol).orElse(null);
      if (price != null) source = "binance";
    }

    PriceQuote q = new PriceQuote(symbol, price, currency, ts, source, null, null);
    try {
      cache.set(key, mapper.writeValueAsString(q), priceTtlSeconds);
    } catch (Exception ignored) {}
    return q;
  }

  public List<PriceQuote> getPricesByContract(int chainId, List<String> contractAddresses, String currency) {
    String cur = normalizeCurrency(currency);
    List<String> addrs =
        contractAddresses.stream()
            .map(a -> a == null ? "" : a.trim().toLowerCase(Locale.ROOT))
            .filter(a -> a.startsWith("0x") && a.length() >= 42)
            .distinct()
            .sorted()
            .toList();

    String requestKey = "req:contracts:" + chainId + ":" + cur + ":" + sha1(String.join(",", addrs));
    Optional<String> cached = cache.get(requestKey);
    if (cached.isPresent()) {
      try {
        return mapper.readValue(cached.get(), new TypeReference<List<PriceQuote>>() {});
      } catch (Exception ignored) {
        // fall through
      }
    }

    long ts = Instant.now().toEpochMilli();
    Map<String, Double> prices = new HashMap<>();
    String source = null;

    // 1) CoinGecko Pro token_price by platform
    String platformId = PriceMappings.COINGECKO_PLATFORMS.get(chainId);
    if (coinGecko.isEnabled() && platformId != null && !addrs.isEmpty() && "usd".equals(cur)) {
      String csv = String.join(",", addrs);
      Map<String, Double> got = coinGecko.fetchTokenPricesByContract(chainId, platformId, csv);
      if (!got.isEmpty()) {
        prices.putAll(got);
        source = "coingecko";
      }
    }

    // 1b) VEILX special-case (BSC) when CoinGecko doesn't have contract price
    if ("usd".equals(cur) && chainId == 56 && veilxDex != null && veilxDex.isEnabled()) {
      String veilxAddr = veilxDex.veilxContractLower();
      if (!veilxAddr.isBlank() && addrs.contains(veilxAddr)) {
        Double v = veilxDex.fetchVeilxUsdPrice().orElse(null);
        if (v != null) {
          // Only fill VEILX contract. Others remain null.
          prices.put(veilxAddr, v);
          if (source == null) source = "pancakeswap_v2";
        }
      }
    }

    // NOTE: CMC/Binance don't support contract-address quoting in a clean way for MVP.
    // If CG doesn't have a contract price, we return null price for that contract.

    List<PriceQuote> out = new ArrayList<>();
    for (String addr : addrs) {
      Double p = prices.get(addr);
      out.add(new PriceQuote(null, p, cur, ts, source, addr, chainId));
    }

    try {
      cache.set(requestKey, mapper.writeValueAsString(out), requestTtlSeconds);
    } catch (Exception ignored) {}

    return out;
  }

  private static String normalizeCurrency(String currency) {
    String c = currency == null ? "usd" : currency.trim().toLowerCase(Locale.ROOT);
    if (c.isBlank()) return "usd";
    // MVP only supports usd; keep signature for future.
    return "usd";
  }

  private static String sha1(String input) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-1");
      byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (byte b : digest) sb.append(String.format("%02x", b));
      return sb.toString();
    } catch (Exception e) {
      return Integer.toHexString(input.hashCode());
    }
  }
}


