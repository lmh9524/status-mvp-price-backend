package io.statusmvp.pricebackend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.statusmvp.pricebackend.model.PortfolioSnapshot;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    // MVP: "all networks" total only needs an EVM multi-chain USD total.
    // Use Ankr's precomputed `totalBalanceUsd` for speed and consistency.
    double totalUsd = fetchTotalBalanceUsdMultiChain(normalizedAddress, chainIds).orElse(0d);

    PortfolioSnapshot snapshot =
        new PortfolioSnapshot(
            normalizedAddress,
            now,
            round2(totalUsd),
            List.of());
    try {
      cache.set(requestKey, mapper.writeValueAsString(snapshot), requestTtlSeconds);
    } catch (Exception ignored) {
      // ignore cache failures
    }
    return snapshot;
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
}
