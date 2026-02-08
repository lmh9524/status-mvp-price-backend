package io.statusmvp.pricebackend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.statusmvp.pricebackend.model.PortfolioChainSummary;
import io.statusmvp.pricebackend.model.PortfolioSnapshot;
import io.statusmvp.pricebackend.model.PriceQuote;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Service
public class PortfolioAggregatorService {
  private static final Logger log = LoggerFactory.getLogger(PortfolioAggregatorService.class);

  private static final Pattern EVM_ADDRESS = Pattern.compile("^0x[0-9a-fA-F]{40}$");

  private static final Map<Integer, ChainMeta> SUPPORTED_CHAINS =
      Map.of(
          1, new ChainMeta("eth", "ETH"),
          10, new ChainMeta("optimism", "ETH"),
          56, new ChainMeta("bsc", "BNB"),
          8453, new ChainMeta("base", "ETH"),
          42161, new ChainMeta("arbitrum", "ETH"));

  private final WebClient webClient;
  private final PriceAggregatorService prices;
  private final RedisCache cache;
  private final ObjectMapper mapper = new ObjectMapper();

  private final String ankrBaseUrl;
  private final String ankrApiKey;
  private final long requestTtlSeconds;
  private final Duration timeout;
  private final List<Integer> defaultChainIds;

  public PortfolioAggregatorService(
      WebClient webClient,
      PriceAggregatorService prices,
      RedisCache cache,
      @Value("${app.portfolio.ankrBaseUrl:https://rpc.ankr.com/multichain}") String ankrBaseUrl,
      @Value(
              "${app.portfolio.ankrApiKey:8ab456e0616fa58794745f952b83f719e7ea3af2d0c9cbac69b8f22323563de7}")
          String ankrApiKey,
      @Value("${app.portfolio.requestTtlSeconds:30}") long requestTtlSeconds,
      @Value("${app.portfolio.timeoutMs:12000}") long timeoutMs,
      @Value("${app.portfolio.defaultChainIds:1,10,56,8453,42161}") String defaultChainIds) {
    this.webClient = webClient;
    this.prices = prices;
    this.cache = cache;
    this.ankrBaseUrl = (ankrBaseUrl == null ? "" : ankrBaseUrl.trim()).replaceAll("/+$", "");
    this.ankrApiKey = ankrApiKey == null ? "" : ankrApiKey.trim();
    this.requestTtlSeconds = requestTtlSeconds;
    this.timeout = Duration.ofMillis(Math.max(1000L, timeoutMs));
    this.defaultChainIds = normalizeChainIds(parseChainIds(defaultChainIds));
  }

  public PortfolioSnapshot getPortfolio(String address, List<Integer> requestedChainIds) {
    String normalizedAddress = normalizeAddress(address);
    List<Integer> chainIds = normalizeChainIds(requestedChainIds);
    String requestKey = "req:portfolio:" + normalizedAddress + ":" + sha1(joinChainIds(chainIds));

    Optional<String> cached = cache.get(requestKey);
    if (cached.isPresent()) {
      try {
        return mapper.readValue(cached.get(), PortfolioSnapshot.class);
      } catch (Exception ignored) {
        // fall through
      }
    }

    long now = Instant.now().toEpochMilli();
    Map<Integer, ChainAssetSnapshot> chainAssets = new LinkedHashMap<>();
    Set<String> nativeSymbols = new LinkedHashSet<>();

    for (int chainId : chainIds) {
      ChainMeta chain = SUPPORTED_CHAINS.get(chainId);
      if (chain == null) continue;
      ChainAssetSnapshot snapshot = fetchChainAssets(normalizedAddress, chainId, chain);
      chainAssets.put(chainId, snapshot);
      nativeSymbols.add(chain.nativeSymbol());
    }

    Map<String, Double> nativePrices = fetchSymbolPrices(nativeSymbols);
    List<PortfolioChainSummary> chains = new ArrayList<>();
    double totalUsd = 0d;

    for (int chainId : chainIds) {
      ChainMeta chain = SUPPORTED_CHAINS.get(chainId);
      if (chain == null) continue;
      ChainAssetSnapshot assets = chainAssets.getOrDefault(chainId, ChainAssetSnapshot.empty());

      Double nativeUsdPrice = nativePrices.get(chain.nativeSymbol());
      double nativeUsdValue =
          nativeUsdPrice != null ? assets.nativeBalance().doubleValue() * nativeUsdPrice : 0d;

      List<TokenPosition> tokens = assets.tokens();
      Map<String, Double> contractPrices = fetchContractPrices(chainId, tokens);
      Map<String, Double> symbolFallbackPrices =
          fetchSymbolFallbackPrices(tokens, contractPrices, chainId, chain.nativeSymbol(), nativeUsdPrice);

      double tokenUsdValue = 0d;
      int pricedTokenCount = 0;
      for (TokenPosition token : tokens) {
        String key = token.contractAddress().toLowerCase(Locale.ROOT);
        Double p = contractPrices.get(key);
        if (p == null) {
          p = symbolFallbackPrices.get(token.symbol().toUpperCase(Locale.ROOT));
        }
        if (p != null && p > 0) {
          tokenUsdValue += token.balance().doubleValue() * p;
          pricedTokenCount++;
        }
      }

      double chainTotalUsd = nativeUsdValue + tokenUsdValue;
      totalUsd += chainTotalUsd;
      chains.add(
          new PortfolioChainSummary(
              chainId,
              chain.blockchain(),
              chain.nativeSymbol(),
              formatDecimal(assets.nativeBalance()),
              nativeUsdPrice,
              round2(nativeUsdValue),
              round2(tokenUsdValue),
              round2(chainTotalUsd),
              tokens.size(),
              pricedTokenCount));
    }

    PortfolioSnapshot snapshot =
        new PortfolioSnapshot(
            normalizedAddress,
            now,
            round2(totalUsd),
            chains);
    try {
      cache.set(requestKey, mapper.writeValueAsString(snapshot), requestTtlSeconds);
    } catch (Exception ignored) {
      // ignore cache failures
    }
    return snapshot;
  }

  public List<Integer> parseChainIds(String chainIds) {
    if (chainIds == null || chainIds.isBlank()) return List.of();
    List<Integer> out = new ArrayList<>();
    for (String part : chainIds.split(",")) {
      String s = part.trim();
      if (s.isBlank()) continue;
      try {
        out.add(Integer.parseInt(s));
      } catch (NumberFormatException ignored) {
        // ignore invalid item
      }
    }
    return out;
  }

  private ChainAssetSnapshot fetchChainAssets(String address, int chainId, ChainMeta chain) {
    if (ankrBaseUrl.isBlank() || ankrApiKey.isBlank()) return ChainAssetSnapshot.empty();
    URI uri = URI.create(ankrBaseUrl + "/" + ankrApiKey);
    Map<String, Object> body =
        Map.of(
            "jsonrpc", "2.0",
            "id", 1,
            "method", "ankr_getAccountBalance",
            "params", Map.of("walletAddress", address, "blockchain", chain.blockchain()));
    try {
      JsonNode root =
          webClient
              .post()
              .uri(uri)
              .bodyValue(body)
              .retrieve()
              .bodyToMono(JsonNode.class)
              .timeout(timeout)
              .block();
      if (root == null || !root.path("error").isMissingNode()) {
        return ChainAssetSnapshot.empty();
      }
      JsonNode result = root.path("result");
      JsonNode assets = result.isArray() ? result : result.path("assets");
      if (assets == null || !assets.isArray()) {
        return ChainAssetSnapshot.empty();
      }

      BigDecimal nativeBalance = BigDecimal.ZERO;
      Map<String, TokenPosition> tokens = new LinkedHashMap<>();
      for (JsonNode asset : assets) {
        if (asset == null || asset.isNull()) continue;
        String symbol = text(asset, "tokenSymbol").toUpperCase(Locale.ROOT);
        BigDecimal balance = parseBalance(asset);
        if (balance.signum() <= 0) continue;

        boolean isNative = asset.path("isNative").asBoolean(false);
        if (isNative || (symbol.equals(chain.nativeSymbol()) && !hasContractAddress(asset))) {
          nativeBalance = nativeBalance.add(balance);
          continue;
        }

        String contract = text(asset, "contractAddress");
        if (!EVM_ADDRESS.matcher(contract).matches()) continue;
        String key = contract.toLowerCase(Locale.ROOT);
        TokenPosition prev = tokens.get(key);
        if (prev == null) {
          tokens.put(key, new TokenPosition(symbol, contract, balance));
        } else {
          tokens.put(key, new TokenPosition(symbol, contract, prev.balance().add(balance)));
        }
      }
      return new ChainAssetSnapshot(nativeBalance, new ArrayList<>(tokens.values()));
    } catch (Exception e) {
      log.warn("portfolio chain fetch failed: chainId={} address={}", chainId, address, e);
      return ChainAssetSnapshot.empty();
    }
  }

  private static boolean hasContractAddress(JsonNode asset) {
    String contract = text(asset, "contractAddress");
    return EVM_ADDRESS.matcher(contract).matches();
  }

  private static String text(JsonNode node, String field) {
    String v = node.path(field).asText("");
    return v == null ? "" : v.trim();
  }

  private static BigDecimal parseBalance(JsonNode asset) {
    String decimalBalance = text(asset, "balance");
    if (!decimalBalance.isBlank()) {
      try {
        return new BigDecimal(decimalBalance);
      } catch (Exception ignored) {
        // fall through
      }
    }
    BigInteger raw =
        parseRaw(text(asset, "balanceRawInteger"),
            parseRaw(text(asset, "tokenBalance"),
                parseRaw(text(asset, "balanceRaw"), null)));
    if (raw == null || raw.signum() <= 0) return BigDecimal.ZERO;
    int decimals = parseInt(text(asset, "tokenDecimals"), 18);
    try {
      return new BigDecimal(raw).movePointLeft(Math.max(0, decimals));
    } catch (Exception ignored) {
      return BigDecimal.ZERO;
    }
  }

  private static BigInteger parseRaw(String value, BigInteger fallback) {
    String v = value == null ? "" : value.trim();
    if (v.isBlank()) return fallback;
    try {
      if (v.startsWith("0x") || v.startsWith("0X")) {
        return new BigInteger(v.substring(2), 16);
      }
      return new BigInteger(v, 10);
    } catch (Exception ignored) {
      return fallback;
    }
  }

  private static int parseInt(String value, int fallback) {
    String v = value == null ? "" : value.trim();
    if (v.isBlank()) return fallback;
    try {
      return Integer.parseInt(v);
    } catch (Exception ignored) {
      return fallback;
    }
  }

  private Map<String, Double> fetchSymbolPrices(Collection<String> symbols) {
    List<String> normalized =
        symbols.stream()
            .map(s -> s == null ? "" : s.trim().toUpperCase(Locale.ROOT))
            .filter(s -> !s.isBlank())
            .distinct()
            .toList();
    if (normalized.isEmpty()) return Map.of();
    Map<String, Double> out = new HashMap<>();
    for (PriceQuote q : prices.getPrices(normalized, "usd")) {
      if (q == null || q.symbol() == null || q.price() == null) continue;
      if (q.price() <= 0) continue;
      out.put(q.symbol().trim().toUpperCase(Locale.ROOT), q.price());
    }
    return out;
  }

  private Map<String, Double> fetchContractPrices(int chainId, List<TokenPosition> tokens) {
    List<String> addrs =
        tokens.stream()
            .map(TokenPosition::contractAddress)
            .map(a -> a == null ? "" : a.trim().toLowerCase(Locale.ROOT))
            .filter(a -> EVM_ADDRESS.matcher(a).matches())
            .distinct()
            .toList();
    if (addrs.isEmpty()) return Map.of();
    Map<String, Double> out = new HashMap<>();
    for (PriceQuote q : prices.getPricesByContract(chainId, addrs, "usd")) {
      if (q == null || q.contractAddress() == null || q.price() == null) continue;
      if (q.price() <= 0) continue;
      out.put(q.contractAddress().trim().toLowerCase(Locale.ROOT), q.price());
    }
    return out;
  }

  private Map<String, Double> fetchSymbolFallbackPrices(
      List<TokenPosition> tokens,
      Map<String, Double> contractPrices,
      int chainId,
      String nativeSymbol,
      Double nativeUsdPrice) {
    Set<String> missingSymbols = new LinkedHashSet<>();
    for (TokenPosition token : tokens) {
      String key = token.contractAddress().toLowerCase(Locale.ROOT);
      if (contractPrices.containsKey(key)) continue;
      String s = token.symbol().trim().toUpperCase(Locale.ROOT);
      if (s.isBlank()) continue;
      // Keep fallback behavior aligned with mobile app.
      if ("WETH".equals(s) && nativeUsdPrice != null && chainId != 56) continue;
      missingSymbols.add(s);
    }
    Map<String, Double> symbolPrices = new HashMap<>(fetchSymbolPrices(missingSymbols));
    if (nativeUsdPrice != null) {
      symbolPrices.putIfAbsent(nativeSymbol.toUpperCase(Locale.ROOT), nativeUsdPrice);
      symbolPrices.putIfAbsent("WETH", nativeUsdPrice);
    }
    return symbolPrices;
  }

  private static String normalizeAddress(String address) {
    String a = address == null ? "" : address.trim();
    if (!EVM_ADDRESS.matcher(a).matches()) {
      throw new IllegalArgumentException("address must be a valid EVM address");
    }
    return a;
  }

  private List<Integer> normalizeChainIds(List<Integer> requestedChainIds) {
    List<Integer> source =
        requestedChainIds == null || requestedChainIds.isEmpty()
            ? defaultChainIds
            : requestedChainIds;
    List<Integer> out = new ArrayList<>();
    for (Integer id : source) {
      if (id == null) continue;
      if (!SUPPORTED_CHAINS.containsKey(id)) continue;
      if (!out.contains(id)) out.add(id);
    }
    if (out.isEmpty()) {
      out.addAll(defaultChainIds.isEmpty() ? List.of(1, 10, 56, 8453, 42161) : defaultChainIds);
    }
    return out;
  }

  private static String formatDecimal(BigDecimal value) {
    if (value == null) return "0";
    BigDecimal cleaned = value.stripTrailingZeros();
    if (cleaned.scale() < 0) cleaned = cleaned.setScale(0, RoundingMode.DOWN);
    return cleaned.toPlainString();
  }

  private static double round2(double value) {
    return Math.round(value * 100.0d) / 100.0d;
  }

  private static String joinChainIds(List<Integer> chainIds) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < chainIds.size(); i++) {
      if (i > 0) sb.append(',');
      sb.append(chainIds.get(i));
    }
    return sb.toString();
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

  private record ChainMeta(String blockchain, String nativeSymbol) {}

  private record TokenPosition(String symbol, String contractAddress, BigDecimal balance) {}

  private record ChainAssetSnapshot(BigDecimal nativeBalance, List<TokenPosition> tokens) {
    static ChainAssetSnapshot empty() {
      return new ChainAssetSnapshot(BigDecimal.ZERO, List.of());
    }
  }
}
