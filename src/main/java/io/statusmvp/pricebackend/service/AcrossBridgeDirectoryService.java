package io.statusmvp.pricebackend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.statusmvp.pricebackend.model.bridge.BridgeAcrossDirectoryResponse;
import io.statusmvp.pricebackend.model.bridge.BridgeAcrossDirectoryResponse.Chain;
import io.statusmvp.pricebackend.model.bridge.BridgeAcrossDirectoryResponse.Route;
import io.statusmvp.pricebackend.model.bridge.BridgeAcrossDirectoryResponse.Token;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Service
public class AcrossBridgeDirectoryService {
  private static final Logger log = LoggerFactory.getLogger(AcrossBridgeDirectoryService.class);

  private static final String CACHE_KEY_CHAINS = "bridge:across:chains";
  private static final String CACHE_KEY_ROUTES = "bridge:across:available-routes";

  private enum AllowlistMode {
    STRICT,
    FULL;

    static AllowlistMode parse(String raw) {
      if (raw == null) return STRICT;
      String v = raw.trim().toUpperCase(Locale.ROOT);
      if (v.isBlank()) return STRICT;
      if ("FULL".equals(v) || "ALL".equals(v)) return FULL;
      return STRICT;
    }
  }

  private final WebClient webClient;
  private final RedisCache cache;
  private final ObjectMapper mapper = new ObjectMapper();

  private final String apiBaseUrl;
  private final Duration timeout;
  private final long chainsCacheTtlSeconds;
  private final long routesCacheTtlSeconds;
  private final AllowlistMode allowlistMode;
  private final List<Long> allowedChainIds;
  private final List<String> allowedTokenSymbolsList;
  private final Set<String> allowedTokenSymbolsSet;

  public AcrossBridgeDirectoryService(
      WebClient webClient,
      RedisCache cache,
      @Value("${app.bridge.across.apiBaseUrl:https://app.across.to/api}") String apiBaseUrl,
      @Value("${app.bridge.across.timeoutMs:12000}") long timeoutMs,
      @Value("${app.bridge.across.chainsCacheTtlSeconds:300}") long chainsCacheTtlSeconds,
      @Value("${app.bridge.across.routesCacheTtlSeconds:60}") long routesCacheTtlSeconds,
      @Value("${app.bridge.across.allowlistMode:STRICT}") String allowlistMode,
      @Value("${app.bridge.across.allowedChainIds:1,10,42161,8453,56}") String allowedChainIds,
      @Value("${app.bridge.across.allowedTokenSymbols:ETH,USDC,USDT,DAI,USDC-BNB,USDT-BNB}") String allowedTokenSymbols) {
    this.webClient = webClient;
    this.cache = cache;
    this.apiBaseUrl = (apiBaseUrl == null ? "" : apiBaseUrl.trim()).replaceAll("/+$", "");
    this.timeout = Duration.ofMillis(Math.max(1000L, timeoutMs));
    this.chainsCacheTtlSeconds = Math.max(5L, chainsCacheTtlSeconds);
    this.routesCacheTtlSeconds = Math.max(5L, routesCacheTtlSeconds);
    this.allowlistMode = AllowlistMode.parse(allowlistMode);
    this.allowedChainIds = parseChainIds(allowedChainIds);
    this.allowedTokenSymbolsList = parseSymbolsUpperList(allowedTokenSymbols);
    this.allowedTokenSymbolsSet = new HashSet<>(allowedTokenSymbolsList);
  }

  public BridgeAcrossDirectoryResponse getDirectory() {
    long now = Instant.now().toEpochMilli();
    if (allowlistMode == AllowlistMode.FULL) {
      List<Chain> chains = fetchAndFilterChains(null, null);
      List<Route> routes = fetchAndFilterRoutes(null, null);

      List<Long> chainIds = new ArrayList<>();
      Set<Long> chainSeen = new HashSet<>();
      for (Chain c : chains) {
        long id = c.chainId();
        if (id > 0 && chainSeen.add(id)) chainIds.add(id);
      }

      Set<String> tokenSymbolsSet = new HashSet<>();
      for (Route r : routes) {
        String in = r.inputTokenSymbol();
        String out = r.outputTokenSymbol();
        if (in != null && !in.isBlank()) tokenSymbolsSet.add(upper(in));
        if (out != null && !out.isBlank()) tokenSymbolsSet.add(upper(out));
      }
      for (Chain c : chains) {
        for (Token t : c.inputTokens()) tokenSymbolsSet.add(upper(t.symbol()));
        for (Token t : c.outputTokens()) tokenSymbolsSet.add(upper(t.symbol()));
      }
      List<String> tokenSymbols = new ArrayList<>(tokenSymbolsSet);
      Collections.sort(tokenSymbols);

      return new BridgeAcrossDirectoryResponse(
          now, new BridgeAcrossDirectoryResponse.Allowlist(chainIds, tokenSymbols), chains, routes);
    }

    List<Long> chainAllow =
        allowedChainIds.isEmpty() ? List.of(1L, 10L, 42161L, 8453L, 56L) : allowedChainIds;
    List<String> tokenAllowList =
        allowedTokenSymbolsList.isEmpty()
            ? List.of("ETH", "USDC", "USDT", "DAI", "USDC-BNB", "USDT-BNB")
            : allowedTokenSymbolsList;
    Set<String> tokenAllowSet =
        allowedTokenSymbolsSet.isEmpty() ? new HashSet<>(tokenAllowList) : allowedTokenSymbolsSet;

    List<Chain> chains = fetchAndFilterChains(chainAllow, tokenAllowSet);
    List<Route> routes = fetchAndFilterRoutes(chainAllow, tokenAllowSet);

    return new BridgeAcrossDirectoryResponse(
        now, new BridgeAcrossDirectoryResponse.Allowlist(chainAllow, tokenAllowList), chains, routes);
  }

  private List<Chain> fetchAndFilterChains(List<Long> chainAllow, Set<String> tokenAllow) {
    if (apiBaseUrl.isBlank()) return List.of();
    String url = apiBaseUrl + "/chains";

    JsonNode root = fetchJsonArrayCached(CACHE_KEY_CHAINS, url, chainsCacheTtlSeconds);
    if (root == null || !root.isArray()) return List.of();

    List<Chain> out = new ArrayList<>();
    for (JsonNode chain : root) {
      long chainId = chain.path("chainId").asLong(0L);
      if (chainId <= 0) continue;
      if (chainAllow != null && !chainAllow.isEmpty() && !chainAllow.contains(chainId)) continue;

      String name = chain.path("name").asText(null);
      String publicRpcUrl = chain.path("publicRpcUrl").asText(null);
      String explorerUrl = chain.path("explorerUrl").asText(null);
      String logoUrl = chain.path("logoUrl").asText(null);
      String spokePool = chain.path("spokePool").asText(null);
      Long spokePoolBlock =
          chain.path("spokePoolBlock").isNumber() ? chain.path("spokePoolBlock").asLong() : null;

      List<Token> inputTokens = mapTokens(chain.path("inputTokens"), tokenAllow);
      List<Token> outputTokens = mapTokens(chain.path("outputTokens"), tokenAllow);

      // Keep chain if it still has at least one token after filtering
      if (inputTokens.isEmpty() && outputTokens.isEmpty()) continue;

      out.add(
          new Chain(
              chainId,
              name,
              publicRpcUrl,
              explorerUrl,
              logoUrl,
              spokePool,
              spokePoolBlock,
              inputTokens,
              outputTokens));
    }

    return out;
  }

  private List<Route> fetchAndFilterRoutes(List<Long> chainAllow, Set<String> tokenAllow) {
    if (apiBaseUrl.isBlank()) return List.of();
    String url = apiBaseUrl + "/available-routes";

    JsonNode root = fetchJsonArrayCached(CACHE_KEY_ROUTES, url, routesCacheTtlSeconds);
    if (root == null || !root.isArray()) return List.of();

    List<Route> out = new ArrayList<>();
    for (JsonNode route : root) {
      long originChainId = route.path("originChainId").asLong(0L);
      long destinationChainId = route.path("destinationChainId").asLong(0L);
      if (originChainId <= 0 || destinationChainId <= 0) continue;
      if (chainAllow != null && !chainAllow.isEmpty()) {
        if (!chainAllow.contains(originChainId) || !chainAllow.contains(destinationChainId)) continue;
      }

      String originTokenSymbol = upper(route.path("originTokenSymbol").asText(""));
      String destinationTokenSymbol = upper(route.path("destinationTokenSymbol").asText(""));
      if (originTokenSymbol.isBlank() || destinationTokenSymbol.isBlank()) continue;
      if (tokenAllow != null && !tokenAllow.isEmpty()) {
        if (!tokenAllow.contains(originTokenSymbol) || !tokenAllow.contains(destinationTokenSymbol))
          continue;
      }

      String originToken = route.path("originToken").asText(null);
      String destinationToken = route.path("destinationToken").asText(null);
      if (originToken == null || originToken.isBlank()) continue;
      if (destinationToken == null || destinationToken.isBlank()) continue;

      boolean isNative = route.path("isNative").asBoolean(false);

      out.add(
          new Route(
              isNative,
              originChainId,
              destinationChainId,
              originToken,
              destinationToken,
              originTokenSymbol,
              destinationTokenSymbol));
    }
    return out;
  }

  private JsonNode fetchJsonArrayCached(String cacheKey, String url, long ttlSeconds) {
    Optional<String> cached = cache.get(cacheKey);
    if (cached.isPresent()) {
      try {
        JsonNode root = mapper.readTree(cached.get());
        if (root != null && root.isArray()) return root;
      } catch (Exception ignored) {
        // fall through
      }
    }

    try {
      String json =
          webClient
              .get()
              .uri(URI.create(url))
              .retrieve()
              .bodyToMono(String.class)
              .timeout(timeout)
              .block();
      if (json == null || json.isBlank()) return null;
      cache.set(cacheKey, json, ttlSeconds);
      JsonNode root = mapper.readTree(json);
      return root != null && root.isArray() ? root : null;
    } catch (Exception e) {
      log.warn("Across fetch failed: url={}", url, e);
      return null;
    }
  }

  private static List<Token> mapTokens(JsonNode tokensNode, Set<String> tokenAllow) {
    if (tokensNode == null || !tokensNode.isArray()) return List.of();
    List<Token> out = new ArrayList<>();
    for (JsonNode t : tokensNode) {
      String symbol = upper(t.path("symbol").asText(""));
      if (symbol.isBlank()) continue;
      if (tokenAllow != null && !tokenAllow.isEmpty() && !tokenAllow.contains(symbol)) continue;

      String address = t.path("address").asText(null);
      String name = t.path("name").asText(null);
      int decimals = t.path("decimals").asInt(0);
      String logoUrl = t.path("logoUrl").asText(null);
      if (decimals <= 0) continue;
      if (address == null || address.isBlank()) continue;
      out.add(new Token(address, symbol, name, decimals, logoUrl));
    }
    return out;
  }

  private static List<Long> parseChainIds(String raw) {
    if (raw == null || raw.isBlank()) return List.of();
    List<Long> out = new ArrayList<>();
    Set<Long> seen = new HashSet<>();
    for (String part : raw.split(",")) {
      String s = part.trim();
      if (s.isBlank()) continue;
      try {
        long id = Long.parseLong(s);
        if (id > 0 && seen.add(id)) out.add(id);
      } catch (NumberFormatException ignored) {
        // ignore invalid item
      }
    }
    return out;
  }

  private static List<String> parseSymbolsUpperList(String raw) {
    if (raw == null || raw.isBlank()) return List.of();
    List<String> out = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (String part : raw.split(",")) {
      String s = upper(part);
      if (s.isBlank()) continue;
      if (seen.contains(s)) continue;
      seen.add(s);
      out.add(s);
    }
    return out;
  }

  private static String upper(String raw) {
    if (raw == null) return "";
    return raw.trim().toUpperCase(Locale.ROOT);
  }
}
