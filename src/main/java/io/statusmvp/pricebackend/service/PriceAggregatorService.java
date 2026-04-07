package io.statusmvp.pricebackend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.statusmvp.pricebackend.client.BinanceClient;
import io.statusmvp.pricebackend.client.CoinGeckoClient;
import io.statusmvp.pricebackend.client.CoinMarketCapClient;
import io.statusmvp.pricebackend.model.PriceMarketData;
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
import java.util.regex.Pattern;
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
  private final PriceHistoryService priceHistory;
  private final ObjectMapper mapper = new ObjectMapper();

  private final long priceTtlSeconds;
  private final long requestTtlSeconds;

  // Accept exchange-friendly symbols (Binance/CMC) to avoid URI encoding failures.
  private static final Pattern SAFE_EXCHANGE_SYMBOL = Pattern.compile("^[A-Z0-9]{1,20}$");
  // Some ecosystems use "USD₮" (USDT) or suffix digits like "USDT0". We normalize those for lookup.
  private static final Pattern STABLE_WITH_SUFFIX_DIGITS =
      Pattern.compile("^(USDC|USDT|DAI|BUSD|TUSD|USDP|GUSD|FRAX|LUSD|SUSD|USDD|USDG|PYUSD|FDUSD|USDE)\\d+$");

  public PriceAggregatorService(
      CoinGeckoClient coinGecko,
      CoinMarketCapClient cmc,
      BinanceClient binance,
      RedisCache cache,
      CoinGeckoIdResolver coinGeckoIds,
      VeilxDexPriceService veilxDex,
      PriceHistoryService priceHistory,
      @Value("${app.cache.priceTtlSeconds:120}") long priceTtlSeconds,
      @Value("${app.cache.requestTtlSeconds:30}") long requestTtlSeconds) {
    this.coinGecko = coinGecko;
    this.cmc = cmc;
    this.binance = binance;
    this.cache = cache;
    this.coinGeckoIds = coinGeckoIds;
    this.veilxDex = veilxDex;
    this.priceHistory = priceHistory;
    this.priceTtlSeconds = priceTtlSeconds;
    this.requestTtlSeconds = requestTtlSeconds;
  }

  public List<PriceQuote> getPrices(List<String> symbols, String currency) {
    String cur = normalizeCurrency(currency);
    List<String> normSymbols =
        symbols.stream()
            // Preserve requested symbol shape (uppercased) in response/caching.
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
    // Use requested symbol as the response key, but normalize for lookup (providers often require ASCII).
    String lookup = normalizeLookupSymbol(symbol);

    // Per-symbol cache
    String key = "price:symbol:" + symbol + ":" + currency;
    Optional<String> cached = cache.get(key);
    if (cached.isPresent()) {
      try {
        return mapper.readValue(cached.get(), PriceQuote.class);
      } catch (Exception ignored) {}
    }

    // 1) CoinGecko Pro (symbol -> id)
    Double price = null;
    Double change24hPct = null;
    String source = null;
    if (coinGecko.isEnabled()) {
      String id = coinGeckoIds.resolve(lookup);
      if (id != null) {
        PriceMarketData marketData = coinGecko.fetchSimpleUsdQuote(id).orElse(null);
        if (marketData != null && positiveOrNull(marketData.price()) != null) {
          price = marketData.price();
          change24hPct = marketData.change24hPct();
          source = "coingecko";
        }
      }
    }

    // 2) CoinMarketCap
    if (price == null && cmc.isEnabled() && isSafeExchangeSymbol(lookup)) {
      PriceMarketData marketData = cmc.fetchUsdQuoteBySymbol(lookup).orElse(null);
      if (marketData != null && positiveOrNull(marketData.price()) != null) {
        price = marketData.price();
        change24hPct = marketData.change24hPct();
        source = "coinmarketcap";
      }
    }

    // 3) Binance (USDT pair)
    if (price == null && isSafeExchangeSymbol(lookup)) {
      PriceMarketData marketData = binance.fetchUsdQuoteViaUsdtPair(lookup).orElse(null);
      if (marketData != null && positiveOrNull(marketData.price()) != null) {
        price = marketData.price();
        change24hPct = marketData.change24hPct();
        source = "binance";
      }
    }

    // 4) VEILX / VIPL on-chain CoinGecko quote. This gives us true 24h market change instead of a
    // Pancake spot-only fallback.
    if ("usd".equals(currency) && coinGecko.isEnabled() && veilxDex != null) {
      String contract = null;
      if ("VEILX".equals(lookup)) {
        contract = veilxDex.veilxContractLower();
      } else if ("VIPL".equals(lookup)) {
        contract = veilxDex.viplContractLower();
      }

      String onchainNetworkId = PriceMappings.COINGECKO_ONCHAIN_NETWORKS.get(56);
      if (contract != null
          && !contract.isBlank()
          && onchainNetworkId != null
          && (price == null || change24hPct == null)) {
        PriceMarketData marketData =
            coinGecko.fetchOnchainTokenQuotes(56, onchainNetworkId, contract, true).get(contract);
        if (marketData != null && positiveOrNull(marketData.price()) != null) {
          if (price == null) {
            price = marketData.price();
          }
          if (change24hPct == null) {
            change24hPct = marketData.change24hPct();
          }
          source = "coingecko_onchain";
        }
      }
    }

    // 5) Stablecoin fallback.
    if (price == null && "usd".equals(currency) && !lookup.isBlank() && PriceMappings.STABLECOINS.contains(lookup)) {
      price = 1.0d;
      change24hPct = null;
      source = "stablecoin_fallback";
    }

    // 6) VEILX / VIPL on-chain DEX pricing as the final fallback when market APIs do not cover them.
    if (price == null && "usd".equals(currency) && veilxDex != null && veilxDex.isEnabled()) {
      if ("VEILX".equals(symbol)) {
        price = veilxDex.fetchVeilxUsdPrice().orElse(null);
        if (price != null) {
          change24hPct = null;
          source = "pancakeswap_v2";
        }
      } else if ("VIPL".equals(symbol)) {
        price = veilxDex.fetchViplUsdPrice().orElse(null);
        if (price != null) {
          change24hPct = null;
          source = "pancakeswap_v2";
        }
      }
    }

    if ("usd".equals(currency) && priceHistory != null && positiveOrNull(price) != null) {
      String historyAssetKey = PriceHistoryService.symbolAssetKey(lookup.isBlank() ? symbol : lookup);
      change24hPct = priceHistory.resolveChange24hPct(historyAssetKey, price, change24hPct, ts);
    }

    PriceQuote q = new PriceQuote(symbol, price, change24hPct, currency, ts, source, null, null);
    try {
      cache.set(key, mapper.writeValueAsString(q), priceTtlSeconds);
    } catch (Exception ignored) {}
    return q;
  }

  private static boolean isSafeExchangeSymbol(String symbol) {
    if (symbol == null || symbol.isBlank()) return false;
    return SAFE_EXCHANGE_SYMBOL.matcher(symbol).matches();
  }

  /**
   * Normalize user-provided symbols to something safe for providers.
   *
   * <p>Important: we keep the original requested symbol in the response, but use this value for lookups.
   */
  private static String normalizeLookupSymbol(String rawUpper) {
    if (rawUpper == null) return "";
    String s = rawUpper.trim().toUpperCase(Locale.ROOT);
    if (s.isBlank()) return "";

    // Common bridged token suffixes: USDC.e -> USDC
    if (s.endsWith(".E")) s = s.substring(0, s.length() - 2);

    // Replace "₮" with "T" so "USD₮" behaves like "USDT" for stablecoin fallback / lookups.
    if (s.indexOf('₮') >= 0) s = s.replace("₮", "T");

    // Some lists append digits (e.g. USDT0). Strip digits for known stablecoins.
    if (STABLE_WITH_SUFFIX_DIGITS.matcher(s).matches()) {
      s = s.replaceAll("\\d+$", "");
    }

    // Provider-facing symbol must be ASCII-ish; if not safe, return empty to skip provider calls.
    if (!SAFE_EXCHANGE_SYMBOL.matcher(s).matches()) {
      return "";
    }

    return s;
  }

  public List<PriceQuote> getPricesByContract(int chainId, List<String> contractAddresses, String currency) {
    String cur = normalizeCurrency(currency);
    List<String> addrs =
        contractAddresses.stream()
            .map(a -> normalizeContractAddress(chainId, a))
            .filter(a -> !a.isBlank())
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
    Map<String, PriceMarketData> quotesByAddress = new HashMap<>();
    Map<String, String> sourceByAddress = new HashMap<>();

    // 1) CoinGecko Pro token_price by platform
    String platformId = PriceMappings.COINGECKO_PLATFORMS.get(chainId);
    if (coinGecko.isEnabled() && platformId != null && !addrs.isEmpty() && "usd".equals(cur)) {
      String csv = String.join(",", addrs);
      Map<String, PriceMarketData> got =
          coinGecko.fetchTokenQuotesByContract(chainId, platformId, csv);
      if (!got.isEmpty()) {
        for (Map.Entry<String, PriceMarketData> entry : got.entrySet()) {
          String addressKey = normalizeContractAddress(chainId, entry.getKey());
          PriceMarketData quote = entry.getValue();
          if (addressKey.isBlank() || quote == null || positiveOrNull(quote.price()) == null) continue;
          quotesByAddress.put(addressKey, quote);
          sourceByAddress.put(addressKey, "coingecko");
        }
      }
    }

    // 1a) CoinGecko Pro onchain fallback. This covers long-tail DEX tokens such as VEILX / VIPL and
    // also gives us 24h change data for chains that aren't pure EVM.
    String onchainNetworkId = PriceMappings.COINGECKO_ONCHAIN_NETWORKS.get(chainId);
    if (coinGecko.isEnabled() && onchainNetworkId != null && !addrs.isEmpty() && "usd".equals(cur)) {
      List<String> onchainTargets =
          addrs.stream()
              .filter(
                  addr -> {
                    PriceMarketData quote = quotesByAddress.get(addr);
                    return quote == null
                        || positiveOrNull(quote.price()) == null
                        || quote.change24hPct() == null;
                  })
              .toList();
      if (!onchainTargets.isEmpty()) {
        String csv = String.join(",", onchainTargets);
        Map<String, PriceMarketData> got =
            coinGecko.fetchOnchainTokenQuotes(chainId, onchainNetworkId, csv, true);
        for (Map.Entry<String, PriceMarketData> entry : got.entrySet()) {
          String addressKey = normalizeContractAddress(chainId, entry.getKey());
          PriceMarketData incoming = entry.getValue();
          if (addressKey.isBlank() || incoming == null || positiveOrNull(incoming.price()) == null) continue;
          PriceMarketData existing = quotesByAddress.get(addressKey);
          if (existing == null || positiveOrNull(existing.price()) == null) {
            quotesByAddress.put(addressKey, incoming);
            sourceByAddress.put(addressKey, "coingecko_onchain");
            continue;
          }
          if (existing.change24hPct() == null && incoming.change24hPct() != null) {
            quotesByAddress.put(addressKey, new PriceMarketData(existing.price(), incoming.change24hPct()));
            sourceByAddress.put(addressKey, "coingecko+onchain");
          }
        }
      }
    }

    // 1b) VEILX / VIPL direct router fallback when Gecko endpoints do not have a usable quote.
    if ("usd".equals(cur) && chainId == 56 && veilxDex != null && veilxDex.isEnabled()) {
      String veilxAddr = veilxDex.veilxContractLower();
      if (!veilxAddr.isBlank() && addrs.contains(veilxAddr)) {
        PriceMarketData existing = quotesByAddress.get(veilxAddr);
        if (existing == null || positiveOrNull(existing.price()) == null) {
          Double v = veilxDex.fetchVeilxUsdPrice().orElse(null);
          if (v != null) {
            quotesByAddress.put(veilxAddr, new PriceMarketData(v, null));
            sourceByAddress.put(veilxAddr, "pancakeswap_v2");
          }
        }
      }
      String viplAddr = veilxDex.viplContractLower();
      if (!viplAddr.isBlank() && addrs.contains(viplAddr)) {
        PriceMarketData existing = quotesByAddress.get(viplAddr);
        if (existing == null || positiveOrNull(existing.price()) == null) {
          Double v = veilxDex.fetchViplUsdPrice().orElse(null);
          if (v != null) {
            quotesByAddress.put(viplAddr, new PriceMarketData(v, null));
            sourceByAddress.put(viplAddr, "pancakeswap_v2");
          }
        }
      }
    }

    List<PriceQuote> out = new ArrayList<>();
    for (String addr : addrs) {
      PriceMarketData quote = quotesByAddress.get(addr);
      Double price = quote != null ? quote.price() : null;
      Double change24hPct = quote != null ? quote.change24hPct() : null;
      if ("usd".equals(cur) && priceHistory != null && positiveOrNull(price) != null) {
        String historyAssetKey = PriceHistoryService.contractAssetKey(chainId, addr);
        change24hPct = priceHistory.resolveChange24hPct(historyAssetKey, price, change24hPct, ts);
      }
      out.add(
          new PriceQuote(
              null,
              price,
              change24hPct,
              cur,
              ts,
              sourceByAddress.get(addr),
              addr,
              chainId));
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

  private static Double positiveOrNull(Double value) {
    if (value == null || !Double.isFinite(value) || value <= 0d) return null;
    return value;
  }

  private static String normalizeContractAddress(int chainId, String raw) {
    if (raw == null) return "";
    String value = raw.trim();
    if (value.isBlank()) return "";
    if (chainId == 195 || chainId == 501) {
      return value;
    }
    if (value.startsWith("0x") && value.length() >= 42) {
      return value.toLowerCase(Locale.ROOT);
    }
    return "";
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
