package io.statusmvp.pricebackend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.statusmvp.pricebackend.model.PortfolioAssetSnapshotV2;
import io.statusmvp.pricebackend.model.PortfolioChainSummary;
import io.statusmvp.pricebackend.model.PortfolioSnapshot;
import io.statusmvp.pricebackend.model.PortfolioSnapshotV2;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
  private static final int V1_SUMMARY_SNAPSHOT_LIMIT = 1000;

  private static final Map<Integer, ChainMeta> SUPPORTED_CHAINS =
      Map.of(
          1, new ChainMeta("eth", "ETH"),
          10, new ChainMeta("optimism", "ETH"),
          56, new ChainMeta("bsc", "BNB"),
          8453, new ChainMeta("base", "ETH"),
          42161, new ChainMeta("arbitrum", "ETH"));

  private final WebClient webClient;
  private final RedisCache cache;
  private final ObjectMapper mapper = new ObjectMapper();

  private final String ankrBaseUrl;
  private final String ankrApiKey;
  private final long requestTtlSeconds;
  private final Duration timeout;
  private final List<Integer> defaultChainIds;

  public PortfolioAggregatorService(
      WebClient webClient,
      RedisCache cache,
      @Value("${app.portfolio.ankrBaseUrl:https://rpc.ankr.com/multichain}") String ankrBaseUrl,
      @Value(
              "${app.portfolio.ankrApiKey:8ab456e0616fa58794745f952b83f719e7ea3af2d0c9cbac69b8f22323563de7}")
          String ankrApiKey,
      @Value("${app.portfolio.requestTtlSeconds:30}") long requestTtlSeconds,
      @Value("${app.portfolio.timeoutMs:12000}") long timeoutMs,
      @Value("${app.portfolio.defaultChainIds:1,10,56,8453,42161}") String defaultChainIds) {
    this.webClient = webClient;
    this.cache = cache;
    this.ankrBaseUrl = (ankrBaseUrl == null ? "" : ankrBaseUrl.trim()).replaceAll("/+$", "");
    this.ankrApiKey = ankrApiKey == null ? "" : ankrApiKey.trim();
    this.requestTtlSeconds = requestTtlSeconds;
    this.timeout = Duration.ofMillis(Math.max(1000L, timeoutMs));
    this.defaultChainIds = normalizeChainIds(parseChainIds(defaultChainIds), true);
  }

  public PortfolioSnapshot getPortfolio(String address, List<Integer> requestedChainIds) {
    return getPortfolio(address, requestedChainIds, false);
  }

  public PortfolioSnapshot getPortfolio(
      String address, List<Integer> requestedChainIds, boolean chainIdsExplicitlyRequested) {
    String normalizedAddress = normalizeAddress(address);
    List<Integer> chainIds = normalizeChainIds(requestedChainIds, !chainIdsExplicitlyRequested);
    long now = Instant.now().toEpochMilli();

    // If caller explicitly requested chainIds but none are supported, return an empty snapshot
    // instead of silently falling back to default mainnet chains.
    if (chainIdsExplicitlyRequested && chainIds.isEmpty()) {
      return new PortfolioSnapshot(normalizedAddress, now, 0d, List.of());
    }

    String requestKey = "req:portfolio:" + normalizedAddress + ":" + sha1(joinChainIds(chainIds));

    Optional<String> cached = cache.get(requestKey);
    if (cached.isPresent()) {
      try {
        return mapper.readValue(cached.get(), PortfolioSnapshot.class);
      } catch (Exception ignored) {
        // fall through
      }
    }

    Optional<PortfolioSnapshotV2> summarySource =
        fetchSnapshotV2FromAnkr(
            normalizedAddress,
            chainIds,
            "usd",
            now,
            new SnapshotFilter(0d, true, V1_SUMMARY_SNAPSHOT_LIMIT));

    List<PortfolioChainSummary> chains = buildPortfolioChainSummaries(chainIds, summarySource.orElse(null));
    Double totalFromSnapshot =
        summarySource
            .map(PortfolioSnapshotV2::totalUsd)
            .filter(v -> v != null && Double.isFinite(v))
            .orElse(null);
    double totalUsd =
        totalFromSnapshot != null
            ? totalFromSnapshot
            : fetchTotalBalanceUsdMultiChain(normalizedAddress, chainIds).orElse(sumChainTotals(chains));

    PortfolioSnapshot snapshot = new PortfolioSnapshot(normalizedAddress, now, round2(totalUsd), chains);
    try {
      cache.set(requestKey, mapper.writeValueAsString(snapshot), requestTtlSeconds);
    } catch (Exception ignored) {
      // ignore cache failures
    }
    return snapshot;
  }

  public PortfolioSnapshotV2 getPortfolioSnapshotV2(
      String address,
      List<Integer> requestedChainIds,
      String currency,
      Double minUsd,
      Boolean includeZero,
      Integer limit) {
    return getPortfolioSnapshotV2(
        address, requestedChainIds, currency, minUsd, includeZero, limit, false);
  }

  public PortfolioSnapshotV2 getPortfolioSnapshotV2(
      String address,
      List<Integer> requestedChainIds,
      String currency,
      Double minUsd,
      Boolean includeZero,
      Integer limit,
      boolean chainIdsExplicitlyRequested) {
    String normalizedAddress = normalizeAddress(address);
    List<Integer> chainIds = normalizeChainIds(requestedChainIds, !chainIdsExplicitlyRequested);
    String cur = normalizeCurrency(currency);
    SnapshotFilter filter = normalizeSnapshotFilter(minUsd, includeZero, limit);
    long now = Instant.now().toEpochMilli();

    if (chainIdsExplicitlyRequested && chainIds.isEmpty()) {
      return new PortfolioSnapshotV2(normalizedAddress, now, cur, 0d, Map.of(), List.of());
    }

    String requestKey =
        "req:portfolio:v2:"
            + cur
            + ":"
            + normalizedAddress
            + ":"
            + filter.minUsd()
            + ":"
            + filter.includeZero()
            + ":"
            + filter.limit()
            + ":"
            + sha1(joinChainIds(chainIds));

    Optional<String> cached = cache.get(requestKey);
    if (cached.isPresent()) {
      try {
        return mapper.readValue(cached.get(), PortfolioSnapshotV2.class);
      } catch (Exception ignored) {
        // fall through
      }
    }

    PortfolioSnapshotV2 snapshot =
        fetchSnapshotV2FromAnkr(normalizedAddress, chainIds, cur, now, filter)
            .orElse(new PortfolioSnapshotV2(normalizedAddress, now, cur, 0d, Map.of(), List.of()));

    try {
      cache.set(requestKey, mapper.writeValueAsString(snapshot), requestTtlSeconds);
    } catch (Exception ignored) {
      // ignore cache failures
    }
    return snapshot;
  }

  private Optional<PortfolioSnapshotV2> fetchSnapshotV2FromAnkr(
      String address, List<Integer> chainIds, String currency, long now, SnapshotFilter filter) {
    if (ankrBaseUrl.isBlank() || ankrApiKey.isBlank()) return Optional.empty();

    List<String> blockchains = new ArrayList<>();
    for (Integer chainId : chainIds) {
      if (chainId == null) continue;
      ChainMeta chain = SUPPORTED_CHAINS.get(chainId);
      if (chain == null) continue;
      String blockchain = chain.blockchain();
      if (blockchain == null || blockchain.isBlank()) continue;
      if (!blockchains.contains(blockchain)) blockchains.add(blockchain);
    }
    if (blockchains.isEmpty()) return Optional.empty();

    URI uri = URI.create(ankrBaseUrl + "/" + ankrApiKey);
    List<JsonNode> assets = new ArrayList<>();
    Double totalUsd = null;

    String nextPageToken = null;
    int pages = 0;
    final int maxPages = 4;
    final int pageSize = 500;

    try {
      do {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("walletAddress", address);
        params.put("blockchain", blockchains);
        params.put("pageSize", pageSize);
        params.put("nativeFirst", true);
        if (nextPageToken != null && !nextPageToken.isBlank()) {
          params.put("pageToken", nextPageToken);
        }

        Map<String, Object> body =
            Map.of(
                "jsonrpc", "2.0",
                "id", 1,
                "method", "ankr_getAccountBalance",
                "params", params);

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
          return Optional.empty();
        }
        JsonNode result = root.path("result");
        if (totalUsd == null) {
          totalUsd = parseDouble(result.path("totalBalanceUsd"));
        }

        JsonNode pageAssets;
        if (result != null && result.isArray()) {
          pageAssets = result;
        } else {
          pageAssets = result.path("assets");
        }
        if (pageAssets != null && pageAssets.isArray()) {
          for (JsonNode asset : pageAssets) {
            if (asset == null || asset.isNull()) continue;
            assets.add(asset);
          }
        }

        nextPageToken = normalizeBlankToNull(result.path("nextPageToken").asText(null));
        pages++;
      } while (nextPageToken != null && pages < maxPages);
    } catch (Exception e) {
      log.warn("portfolio snapshot v2 fetch failed: address={}", address, e);
      return Optional.empty();
    }

    Map<String, Integer> chainIdByBlockchain = new HashMap<>();
    for (Map.Entry<Integer, ChainMeta> e : SUPPORTED_CHAINS.entrySet()) {
      String b = e.getValue().blockchain();
      if (b == null || b.isBlank()) continue;
      chainIdByBlockchain.put(b.toLowerCase(Locale.ROOT), e.getKey());
    }

    List<PortfolioAssetSnapshotV2> out = new ArrayList<>();
    Map<Integer, Long> blockNumbersByChainId = new HashMap<>();
    double fallbackTotal = 0d;

    for (JsonNode asset : assets) {
      String blockchain = normalizeBlankToNull(asset.path("blockchain").asText(null));
      if (blockchain == null) continue;
      Integer chainId = chainIdByBlockchain.get(blockchain.toLowerCase(Locale.ROOT));
      if (chainId == null || !chainIds.contains(chainId)) continue;

      boolean isNative = asset.path("isNative").asBoolean(false);
      String symbol = normalizeBlankToNull(asset.path("tokenSymbol").asText(null));
      if (symbol == null) continue;
      String name = normalizeBlankToNull(asset.path("tokenName").asText(null));
      Integer decimals = parseInt(asset.path("tokenDecimals"));

      String rawStr =
          firstNonBlankText(
              asset.path("balanceRawInteger"),
              asset.path("tokenBalance"),
              asset.path("balanceRaw"),
              asset.path("balance"));
      if (rawStr == null) continue;
      BigInteger raw = parseBigInteger(rawStr);
      if (raw == null) continue;

      boolean isZeroBalance = raw.signum() <= 0;
      if (!filter.includeZero() && isZeroBalance) {
        continue;
      }

      String contract = null;
      if (!isNative) {
        contract = normalizeBlankToNull(asset.path("contractAddress").asText(null));
        if (contract == null || !contract.startsWith("0x") || contract.length() < 42) {
          continue;
        }
      }

      String balance = decimals != null ? formatUnits(raw, decimals) : null;
      Double usdValue = parseDouble(asset.path("balanceUsd"));
      Double usdPrice = parseDouble(asset.path("tokenPrice"));
      if (usdValue != null && usdValue > 0 && usdValue < filter.minUsd()) {
        continue;
      }
      String logoUrl = normalizeBlankToNull(asset.path("thumbnail").asText(null));
      Long blockNumber = parseLong(asset.path("blockHeight"));
      if (blockNumber != null) {
        Long prev = blockNumbersByChainId.get(chainId);
        if (prev == null || blockNumber > prev) {
          blockNumbersByChainId.put(chainId, blockNumber);
        }
      }

      if (usdValue != null && usdValue > 0) {
        fallbackTotal += usdValue;
      }

      out.add(
          new PortfolioAssetSnapshotV2(
              chainId,
              blockchain,
              isNative,
              contract,
              symbol,
              name,
              decimals,
              raw.toString(),
              balance,
              usdPrice,
              usdValue,
              logoUrl,
              blockNumber));
    }
    out.sort(
        (a, b) -> {
          double av = a.usdValue() != null ? a.usdValue() : 0d;
          double bv = b.usdValue() != null ? b.usdValue() : 0d;
          int cmp = Double.compare(bv, av);
          if (cmp != 0) return cmp;
          return a.symbol().compareToIgnoreCase(b.symbol());
        });
    if (out.size() > filter.limit()) {
      out = new ArrayList<>(out.subList(0, filter.limit()));
    }

    double finalTotal = totalUsd != null ? totalUsd : fallbackTotal;
    return Optional.of(
        new PortfolioSnapshotV2(
            address,
            now,
            currency,
            round2(finalTotal),
            Map.copyOf(blockNumbersByChainId),
            out));
  }

  private Optional<Double> fetchTotalBalanceUsdMultiChain(String address, List<Integer> chainIds) {
    if (ankrBaseUrl.isBlank() || ankrApiKey.isBlank()) return Optional.empty();
    List<String> blockchains = new ArrayList<>();
    for (Integer chainId : chainIds) {
      if (chainId == null) continue;
      ChainMeta chain = SUPPORTED_CHAINS.get(chainId);
      if (chain == null) continue;
      String blockchain = chain.blockchain();
      if (blockchain == null || blockchain.isBlank()) continue;
      if (!blockchains.contains(blockchain)) blockchains.add(blockchain);
    }
    if (blockchains.isEmpty()) return Optional.empty();

    URI uri = URI.create(ankrBaseUrl + "/" + ankrApiKey);
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("walletAddress", address);
    params.put("blockchain", blockchains);
    params.put("onlyWhitelisted", true);
    params.put("pageSize", 1);
    params.put("nativeFirst", true);

    Map<String, Object> body =
        Map.of(
            "jsonrpc", "2.0",
            "id", 1,
            "method", "ankr_getAccountBalance",
            "params", params);

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
        return Optional.empty();
      }
      JsonNode result = root.path("result");
      Double total = parseDouble(result.path("totalBalanceUsd"));
      if (total != null) return Optional.of(total);

      // Fallback: sum visible `balanceUsd` from the first page when total isn't present.
      JsonNode assets = result.path("assets");
      if (assets != null && assets.isArray()) {
        double sum = 0d;
        boolean any = false;
        for (JsonNode asset : assets) {
          if (asset == null || asset.isNull()) continue;
          Double usd = parseDouble(asset.path("balanceUsd"));
          if (usd == null) continue;
          if (usd <= 0) continue;
          any = true;
          sum += usd;
        }
        if (any) return Optional.of(sum);
      }
      return Optional.empty();
    } catch (Exception e) {
      log.warn("portfolio multi-chain fetch failed: address={}", address, e);
      return Optional.empty();
    }
  }

  private static Double parseDouble(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) return null;
    if (node.isNumber()) return node.asDouble();
    String s = node.asText("");
    if (s == null) return null;
    s = s.trim();
    if (s.isBlank()) return null;
    try {
      return Double.parseDouble(s);
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private static Integer parseInt(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) return null;
    if (node.isNumber()) return node.asInt();
    String s = node.asText("");
    if (s == null) return null;
    s = s.trim();
    if (s.isBlank()) return null;
    try {
      return Integer.parseInt(s);
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private static Long parseLong(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) return null;
    if (node.isNumber()) return node.asLong();
    String s = node.asText("");
    if (s == null) return null;
    s = s.trim();
    if (s.isBlank()) return null;
    try {
      return Long.parseLong(s);
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private static String normalizeBlankToNull(String value) {
    String v = value == null ? null : value.trim();
    return (v == null || v.isBlank()) ? null : v;
  }

  private static String firstNonBlankText(JsonNode... nodes) {
    if (nodes == null) return null;
    for (JsonNode n : nodes) {
      if (n == null || n.isMissingNode() || n.isNull()) continue;
      String t = normalizeBlankToNull(n.asText(null));
      if (t != null) return t;
    }
    return null;
  }

  private static BigInteger parseBigInteger(String raw) {
    String s = normalizeBlankToNull(raw);
    if (s == null) return null;
    try {
      return s.startsWith("0x") ? new BigInteger(s.substring(2), 16) : new BigInteger(s);
    } catch (Exception ignored) {
      return null;
    }
  }

  private static String formatUnits(BigInteger raw, int decimals) {
    if (raw == null) return null;
    if (decimals <= 0) return raw.toString();
    BigDecimal v = new BigDecimal(raw).movePointLeft(decimals);
    String out = v.stripTrailingZeros().toPlainString();
    if ("-0".equals(out)) return "0";
    return out;
  }

  private static String normalizeCurrency(String currency) {
    String c = currency == null ? "usd" : currency.trim().toLowerCase(Locale.ROOT);
    if (c.isBlank()) return "usd";
    // MVP: only support usd, but keep signature for future.
    return "usd";
  }

  private static SnapshotFilter normalizeSnapshotFilter(
      Double minUsdInput, Boolean includeZeroInput, Integer limitInput) {
    double minUsd = minUsdInput == null ? 0.01d : minUsdInput;
    if (!Double.isFinite(minUsd) || minUsd < 0d) minUsd = 0d;
    minUsd = round2(minUsd);

    boolean includeZero = includeZeroInput != null && includeZeroInput;

    int limit = limitInput == null ? 200 : limitInput;
    if (limit < 1) limit = 1;
    if (limit > 1000) limit = 1000;

    return new SnapshotFilter(minUsd, includeZero, limit);
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

  private static String normalizeAddress(String address) {
    String a = address == null ? "" : address.trim();
    if (!EVM_ADDRESS.matcher(a).matches()) {
      throw new IllegalArgumentException("address must be a valid EVM address");
    }
    return a;
  }

  private List<Integer> normalizeChainIds(List<Integer> requestedChainIds, boolean fallbackToDefaultWhenEmpty) {
    List<Integer> source = requestedChainIds == null ? List.of() : requestedChainIds;
    if (source.isEmpty() && fallbackToDefaultWhenEmpty) {
      source = defaultChainIds;
    }
    List<Integer> out = new ArrayList<>();
    for (Integer id : source) {
      if (id == null) continue;
      if (!SUPPORTED_CHAINS.containsKey(id)) continue;
      if (!out.contains(id)) out.add(id);
    }
    if (out.isEmpty() && fallbackToDefaultWhenEmpty) {
      out.addAll(defaultChainIds.isEmpty() ? List.of(1, 10, 56, 8453, 42161) : defaultChainIds);
    }
    return out;
  }

  private List<PortfolioChainSummary> buildPortfolioChainSummaries(
      List<Integer> chainIds, PortfolioSnapshotV2 snapshot) {
    if (chainIds == null || chainIds.isEmpty()) return List.of();

    Map<Integer, ChainSummaryAccumulator> accByChainId = new LinkedHashMap<>();
    for (Integer chainId : chainIds) {
      if (chainId == null) continue;
      ChainMeta meta = SUPPORTED_CHAINS.get(chainId);
      String blockchain = meta != null ? meta.blockchain() : "unknown";
      String nativeSymbol = meta != null ? meta.nativeSymbol() : "";
      accByChainId.put(chainId, new ChainSummaryAccumulator(blockchain, nativeSymbol));
    }

    if (snapshot != null && snapshot.assets() != null) {
      for (PortfolioAssetSnapshotV2 asset : snapshot.assets()) {
        if (asset == null) continue;
        ChainSummaryAccumulator acc = accByChainId.get(asset.chainId());
        if (acc == null) continue;

        String blockchain = normalizeBlankToNull(asset.blockchain());
        if (blockchain != null) acc.blockchain = blockchain;

        if (asset.isNative()) {
          String nativeBalance = normalizeBlankToNull(asset.balance());
          if (nativeBalance != null) acc.nativeBalance = nativeBalance;

          Double nativeUsdPrice = positiveOrNull(asset.usdPrice());
          if (nativeUsdPrice != null) acc.nativeUsdPrice = nativeUsdPrice;

          Double nativeUsdValue = positiveOrNull(asset.usdValue());
          if (nativeUsdValue != null) {
            acc.nativeUsdValue = (acc.nativeUsdValue == null ? 0d : acc.nativeUsdValue) + nativeUsdValue;
          }
          continue;
        }

        acc.tokenCount += 1;
        Double tokenUsdPrice = positiveOrNull(asset.usdPrice());
        if (tokenUsdPrice != null) acc.pricedTokenCount += 1;

        Double tokenUsdValue = positiveOrNull(asset.usdValue());
        if (tokenUsdValue != null) acc.tokenUsdValue += tokenUsdValue;
      }
    }

    List<PortfolioChainSummary> out = new ArrayList<>();
    for (Integer chainId : chainIds) {
      ChainSummaryAccumulator acc = accByChainId.get(chainId);
      if (acc == null) continue;

      Double nativeUsdPrice = positiveOrNull(acc.nativeUsdPrice);
      Double nativeUsdValue = positiveOrNull(acc.nativeUsdValue);
      Double tokenUsdValue = acc.tokenUsdValue > 0 ? round2(acc.tokenUsdValue) : null;
      double totalUsdRaw = (nativeUsdValue != null ? nativeUsdValue : 0d) + (tokenUsdValue != null ? tokenUsdValue : 0d);

      out.add(
          new PortfolioChainSummary(
              chainId,
              acc.blockchain,
              acc.nativeSymbol,
              acc.nativeBalance,
              nativeUsdPrice,
              nativeUsdValue != null ? round2(nativeUsdValue) : null,
              tokenUsdValue,
              round2(totalUsdRaw),
              acc.tokenCount,
              acc.pricedTokenCount));
    }
    return out;
  }

  private static Double positiveOrNull(Double value) {
    if (value == null || !Double.isFinite(value) || value <= 0d) return null;
    return value;
  }

  private static double sumChainTotals(List<PortfolioChainSummary> chains) {
    if (chains == null || chains.isEmpty()) return 0d;
    double sum = 0d;
    for (PortfolioChainSummary chain : chains) {
      if (chain == null || chain.totalUsd() == null) continue;
      Double total = chain.totalUsd();
      if (total == null || !Double.isFinite(total) || total <= 0d) continue;
      sum += total;
    }
    return sum;
  }

  private static double round2(double value) {
    return Math.round(value * 100.0d) / 100.0d;
  }

  private record SnapshotFilter(double minUsd, boolean includeZero, int limit) {}

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

  private static final class ChainSummaryAccumulator {
    private String blockchain;
    private final String nativeSymbol;
    private String nativeBalance = "0";
    private Double nativeUsdPrice;
    private Double nativeUsdValue;
    private double tokenUsdValue;
    private int tokenCount;
    private int pricedTokenCount;

    private ChainSummaryAccumulator(String blockchain, String nativeSymbol) {
      this.blockchain = blockchain;
      this.nativeSymbol = nativeSymbol;
    }
  }

  private record ChainMeta(String blockchain, String nativeSymbol) {}
}
