package io.statusmvp.pricebackend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.statusmvp.pricebackend.model.token.TokenSearchItem;
import io.statusmvp.pricebackend.model.token.TokenSearchResponse;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Service
public class TokenCatalogService {
  private static final Logger log = LoggerFactory.getLogger(TokenCatalogService.class);

  private static final String CACHE_PREFIX = "token-catalog:v1:merged:";
  private static final String SOURCE_DEFAULT = "default";
  private static final String SOURCE_OPENOCEAN = "openocean";
  private static final String SOURCE_TRUSTWALLET = "trustwallet";
  private static final String SOURCE_JUPITER = "jupiter";
  private static final String SOURCE_ONCHAIN = "onchain";

  private static final Set<Integer> OPENOCEAN_CHAIN_IDS = Set.of(1, 10, 56, 137, 42161, 8453);
  private static final Map<Integer, String> TRUSTWALLET_SLUG_BY_CHAIN_ID =
      Map.ofEntries(
          Map.entry(1, "ethereum"),
          Map.entry(10, "optimism"),
          Map.entry(56, "smartchain"),
          Map.entry(137, "polygon"),
          Map.entry(42161, "arbitrum"),
          Map.entry(8453, "base"),
          Map.entry(195, "tron"));
  private static final Map<Integer, String> ALCHEMY_NETWORK_BY_CHAIN_ID =
      Map.ofEntries(
          Map.entry(1, "eth-mainnet"),
          Map.entry(10, "opt-mainnet"),
          Map.entry(137, "polygon-mainnet"),
          Map.entry(196, "xlayer-mainnet"),
          Map.entry(42161, "arb-mainnet"),
          Map.entry(8453, "base-mainnet"));
  private static final Map<String, Integer> POPULAR_SYMBOL_RANK =
      Map.ofEntries(
          Map.entry("USDC", 0),
          Map.entry("USDT", 1),
          Map.entry("DAI", 2),
          Map.entry("WETH", 3),
          Map.entry("WBNB", 4),
          Map.entry("WOKB", 5),
          Map.entry("WBTC", 6),
          Map.entry("LINK", 7),
          Map.entry("UNI", 8),
          Map.entry("AAVE", 9));

  private final WebClient webClient;
  private final RedisCache cache;
  private final ObjectMapper mapper;
  private final boolean enabled;
  private final Duration timeout;
  private final Duration alchemyTimeout;
  private final long refreshTtlSeconds;
  private final long redisTtlSeconds;
  private final Set<Integer> allowedChainIds;
  private final String openOceanBaseUrl;
  private final String trustWalletBaseUrl;
  private final String jupiterTokenListUrl;
  private final String alchemyApiKey;
  private final Set<Integer> refreshInFlight = ConcurrentHashMap.newKeySet();

  public TokenCatalogService(
      WebClient webClient,
      RedisCache cache,
      ObjectMapper mapper,
      @Value("${app.tokenCatalog.enabled:true}") boolean enabled,
      @Value("${app.tokenCatalog.timeoutMs:12000}") long timeoutMs,
      @Value("${app.tokenCatalog.alchemyTimeoutMs:6000}") long alchemyTimeoutMs,
      @Value("${app.tokenCatalog.refreshTtlSeconds:43200}") long refreshTtlSeconds,
      @Value("${app.tokenCatalog.redisTtlSeconds:604800}") long redisTtlSeconds,
      @Value("${app.tokenCatalog.allowedChainIds:1,10,56,137,196,42161,8453,195,501}") String allowedChainIds,
      @Value("${app.tokenCatalog.openOceanBaseUrl:https://open-api.openocean.finance/v3}") String openOceanBaseUrl,
      @Value("${app.tokenCatalog.trustWalletBaseUrl:https://cdn.jsdelivr.net/gh/trustwallet/assets@master/blockchains}") String trustWalletBaseUrl,
      @Value("${app.tokenCatalog.jupiterTokenListUrl:https://token.jup.ag/strict}") String jupiterTokenListUrl,
      @Value("${app.tokenCatalog.alchemyApiKey:}") String alchemyApiKey) {
    this.webClient = webClient;
    this.cache = cache;
    this.mapper = mapper;
    this.enabled = enabled;
    this.timeout = Duration.ofMillis(Math.max(1000L, timeoutMs));
    this.alchemyTimeout = Duration.ofMillis(Math.max(1000L, alchemyTimeoutMs));
    this.refreshTtlSeconds = Math.max(60L, refreshTtlSeconds);
    this.redisTtlSeconds = Math.max(this.refreshTtlSeconds, redisTtlSeconds);
    this.allowedChainIds = parseChainIds(allowedChainIds);
    this.openOceanBaseUrl = normalizeBaseUrl(openOceanBaseUrl);
    this.trustWalletBaseUrl = normalizeBaseUrl(trustWalletBaseUrl);
    this.jupiterTokenListUrl = jupiterTokenListUrl == null ? "" : jupiterTokenListUrl.trim();
    this.alchemyApiKey = alchemyApiKey == null ? "" : alchemyApiKey.trim();
  }

  public boolean isEnabled() {
    return enabled;
  }

  public boolean isAllowedChainId(int chainId) {
    return enabled && allowedChainIds.contains(chainId);
  }

  public TokenSearchResponse search(int chainId, String query, int limit) {
    CatalogSnapshot catalog = loadCatalog(chainId);
    String normalizedQuery = normalizeQuery(query);
    int max = clampLimit(limit);
    List<TokenSearchItem> results =
        new ArrayList<>(
            catalog.items().stream()
                .filter(item -> matches(item, normalizedQuery))
                .sorted(searchComparator(normalizedQuery))
                .limit(max)
                .toList());

    if (isLikelyEvmAddress(normalizedQuery)
        && results.stream().noneMatch(item -> addressKey(chainId, item.address()).equals(addressKey(chainId, normalizedQuery)))) {
      Optional<TokenSearchItem> onchain = lookupOnchainEvmToken(chainId, normalizedQuery);
      if (onchain.isPresent()) results.add(0, onchain.get());
    }

    if (results.size() > max) {
      results = results.subList(0, max);
    }
    return new TokenSearchResponse(chainId, normalizedQuery, catalog.fetchedAt(), catalog.stale(), results);
  }

  public TokenSearchResponse lookup(int chainId, String address) {
    CatalogSnapshot catalog = loadCatalog(chainId);
    String key = addressKey(chainId, address);
    List<TokenSearchItem> out =
        catalog.items().stream()
            .filter(item -> addressKey(chainId, item.address()).equals(key))
            .limit(1)
            .toList();
    if (out.isEmpty()) {
      Optional<TokenSearchItem> onchain = lookupOnchainEvmToken(chainId, address);
      if (onchain.isPresent()) out = List.of(onchain.get());
    }
    return new TokenSearchResponse(chainId, address == null ? "" : address.trim(), catalog.fetchedAt(), catalog.stale(), out);
  }

  private CatalogSnapshot loadCatalog(int chainId) {
    long now = Instant.now().toEpochMilli();
    Optional<CachedCatalog> cached = readCachedCatalog(chainId);
    if (cached.isPresent()) {
      CachedCatalog c = cached.get();
      boolean stale = now - c.fetchedAt() > refreshTtlSeconds * 1000L;
      if (stale) triggerBackgroundRefresh(chainId);
      return new CatalogSnapshot(c.fetchedAt(), stale, c.items());
    }
    try {
      CachedCatalog fresh = refreshCatalog(chainId);
      return new CatalogSnapshot(fresh.fetchedAt(), false, fresh.items());
    } catch (Exception e) {
      log.warn("token catalog cold refresh failed: chainId={}", chainId, e);
      return new CatalogSnapshot(now, true, List.of());
    }
  }

  private void triggerBackgroundRefresh(int chainId) {
    if (!refreshInFlight.add(chainId)) return;
    CompletableFuture.runAsync(
            () -> {
              try {
                refreshCatalog(chainId);
              } catch (Exception e) {
                log.warn("token catalog background refresh failed: chainId={}", chainId, e);
              } finally {
                refreshInFlight.remove(chainId);
              }
            });
  }

  private CachedCatalog refreshCatalog(int chainId) {
    LinkedHashMap<String, MutableToken> merged = new LinkedHashMap<>();
    for (TokenSearchItem item : defaultTokens(chainId)) {
      addOrMerge(merged, item);
    }
    if (OPENOCEAN_CHAIN_IDS.contains(chainId)) {
      for (TokenSearchItem item : fetchOpenOcean(chainId)) addOrMerge(merged, item);
    }
    for (TokenSearchItem item : fetchTrustWallet(chainId)) addOrMerge(merged, item);
    if (chainId == 501) {
      for (TokenSearchItem item : fetchJupiter()) addOrMerge(merged, item);
    }
    List<TokenSearchItem> items =
        merged.values().stream()
            .map(MutableToken::toItem)
            .sorted(searchComparator(""))
            .toList();
    CachedCatalog payload = new CachedCatalog(chainId, Instant.now().toEpochMilli(), items);
    try {
      cache.set(cacheKey(chainId), mapper.writeValueAsString(payload), redisTtlSeconds);
    } catch (Exception e) {
      log.warn("token catalog cache write failed: chainId={}", chainId, e);
    }
    return payload;
  }

  private List<TokenSearchItem> fetchOpenOcean(int chainId) {
    if (openOceanBaseUrl.isBlank()) return List.of();
    String url = openOceanBaseUrl + "/" + chainId + "/tokenList";
    JsonNode root = fetchJson(url);
    if (root == null) return List.of();
    JsonNode data = root.path("data");
    if (!data.isArray()) return List.of();
    List<TokenSearchItem> out = new ArrayList<>();
    for (JsonNode node : data) {
      String address = node.path("address").asText("").trim();
      if (!isLikelyEvmAddress(address)) continue;
      String symbol = node.path("symbol").asText("").trim();
      int decimals = node.path("decimals").asInt(-1);
      if (symbol.isBlank() || decimals < 0) continue;
      out.add(
          new TokenSearchItem(
              chainId,
              address,
              "erc20",
              symbol,
              textOrNull(node.path("name").asText(null)),
              decimals,
              normalizeLogoUri(node.path("icon").asText(null)),
              List.of(SOURCE_OPENOCEAN),
              "dex-list"));
    }
    return out;
  }

  private List<TokenSearchItem> fetchTrustWallet(int chainId) {
    String slug = TRUSTWALLET_SLUG_BY_CHAIN_ID.get(chainId);
    if (slug == null || trustWalletBaseUrl.isBlank()) return List.of();
    JsonNode root = fetchJson(trustWalletBaseUrl + "/" + slug + "/tokenlist.json");
    JsonNode tokens = root == null ? null : root.path("tokens");
    if (tokens == null || !tokens.isArray()) return List.of();
    List<TokenSearchItem> out = new ArrayList<>();
    for (JsonNode node : tokens) {
      String address = node.path("address").asText("").trim();
      if (!isValidAddressForChain(chainId, address)) continue;
      String symbol = node.path("symbol").asText("").trim();
      int decimals = node.path("decimals").asInt(-1);
      if (symbol.isBlank() || decimals < 0) continue;
      out.add(
          new TokenSearchItem(
              chainId,
              address,
              standardForChain(chainId),
              symbol,
              textOrNull(node.path("name").asText(null)),
              decimals,
              normalizeLogoUri(node.path("logoURI").asText(null)),
              List.of(SOURCE_TRUSTWALLET),
              "curated"));
    }
    return out;
  }

  private List<TokenSearchItem> fetchJupiter() {
    if (jupiterTokenListUrl.isBlank()) return List.of();
    JsonNode root = fetchJson(jupiterTokenListUrl);
    JsonNode tokens = root != null && root.isArray() ? root : root == null ? null : root.path("tokens");
    if (tokens == null || !tokens.isArray()) return List.of();
    List<TokenSearchItem> out = new ArrayList<>();
    for (JsonNode node : tokens) {
      String address = node.path("address").asText("").trim();
      String symbol = node.path("symbol").asText("").trim();
      int decimals = node.path("decimals").asInt(-1);
      if (address.isBlank() || symbol.isBlank() || decimals < 0) continue;
      out.add(
          new TokenSearchItem(
              501,
              address,
              "spl",
              symbol,
              textOrNull(node.path("name").asText(null)),
              decimals,
              textOrNull(node.path("logoURI").asText(null)),
              List.of(SOURCE_JUPITER),
              "curated"));
    }
    return out;
  }

  private Optional<TokenSearchItem> lookupOnchainEvmToken(int chainId, String address) {
    if (!isLikelyEvmAddress(address) || alchemyApiKey.isBlank()) return Optional.empty();
    String network = ALCHEMY_NETWORK_BY_CHAIN_ID.get(chainId);
    if (network == null) return Optional.empty();
    try {
      ObjectNode body = mapper.createObjectNode();
      body.put("jsonrpc", "2.0");
      body.put("id", 1);
      body.put("method", "alchemy_getTokenMetadata");
      body.putArray("params").add(address.trim());
      String json =
          webClient
              .post()
              .uri(URI.create("https://" + network + ".g.alchemy.com/v2/" + alchemyApiKey))
              .bodyValue(body)
              .retrieve()
              .bodyToMono(String.class)
              .timeout(alchemyTimeout)
              .block();
      if (json == null || json.isBlank()) return Optional.empty();
      JsonNode result = mapper.readTree(json).path("result");
      String symbol = result.path("symbol").asText("").trim();
      int decimals = result.path("decimals").asInt(-1);
      if (symbol.isBlank() || decimals < 0) return Optional.empty();
      return Optional.of(
          new TokenSearchItem(
              chainId,
              address.trim(),
              "erc20",
              symbol,
              textOrNull(result.path("name").asText(null)),
              decimals,
              textOrNull(result.path("logo").asText(null)),
              List.of(SOURCE_ONCHAIN),
              "contract"));
    } catch (Exception e) {
      log.warn("token catalog alchemy lookup failed: chainId={}, address={}", chainId, shortAddress(address), e);
      return Optional.empty();
    }
  }

  private JsonNode fetchJson(String url) {
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
      return mapper.readTree(json);
    } catch (Exception e) {
      log.warn("token catalog upstream fetch failed: url={}", scrubUrl(url), e);
      return null;
    }
  }

  private Optional<CachedCatalog> readCachedCatalog(int chainId) {
    try {
      Optional<String> raw = cache.get(cacheKey(chainId));
      if (raw.isEmpty() || raw.get().isBlank()) return Optional.empty();
      return Optional.of(mapper.readValue(raw.get(), new TypeReference<CachedCatalog>() {}));
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  private void addOrMerge(Map<String, MutableToken> merged, TokenSearchItem item) {
    String key = addressKey(item.chainId(), item.address());
    if (key.isBlank()) return;
    MutableToken existing = merged.get(key);
    if (existing == null) {
      merged.put(key, MutableToken.from(item));
    } else {
      existing.merge(item);
    }
  }

  private Comparator<TokenSearchItem> searchComparator(String query) {
    return (left, right) -> {
      int scoreDiff = score(left, query) - score(right, query);
      if (scoreDiff != 0) return scoreDiff;
      if (query == null || query.isBlank()) {
        int popularDiff = popularRank(left) - popularRank(right);
        if (popularDiff != 0) return popularDiff;
      }
      int confidenceDiff = confidenceRank(left.confidence()) - confidenceRank(right.confidence());
      if (confidenceDiff != 0) return confidenceDiff;
      return left.symbol().compareToIgnoreCase(right.symbol());
    };
  }

  private static int popularRank(TokenSearchItem item) {
    String symbol = upper(item.symbol());
    int chainRank =
        switch (item.chainId()) {
          case 1, 10, 42161 -> switch (symbol) {
            case "USDC" -> 0;
            case "USDT" -> 1;
            case "DAI" -> 2;
            case "WETH" -> 3;
            default -> 10_000;
          };
          case 56 -> switch (symbol) {
            case "USDC" -> 0;
            case "USDT" -> 1;
            case "WBNB" -> 2;
            case "DAI" -> 3;
            default -> 10_000;
          };
          case 137 -> switch (symbol) {
            case "USDC" -> 0;
            case "USDT" -> 1;
            case "DAI" -> 2;
            case "WETH" -> 3;
            case "WMATIC" -> 4;
            default -> 10_000;
          };
          case 8453 -> switch (symbol) {
            case "USDC" -> 0;
            case "WETH" -> 1;
            case "DAI" -> 2;
            case "USDT" -> 3;
            default -> 10_000;
          };
          case 196 -> "USDC".equals(symbol) ? 0 : 10_000;
          case 195 -> "USDT".equals(symbol) ? 0 : 10_000;
          case 501 -> switch (symbol) {
            case "USDC" -> 0;
            case "USDT" -> 1;
            case "SOL", "WSOL" -> 2;
            default -> 10_000;
          };
          default -> 10_000;
        };
    if (chainRank < 10_000) return chainRank;
    Integer globalRank = POPULAR_SYMBOL_RANK.get(symbol);
    return globalRank == null ? 20_000 : 1_000 + globalRank;
  }

  private static int score(TokenSearchItem item, String query) {
    if (query == null || query.isBlank()) return 0;
    String q = query.toLowerCase(Locale.ROOT);
    String symbol = lower(item.symbol());
    String name = lower(item.name());
    String address = lower(item.address());
    if (symbol.equals(q)) return 0;
    if (name.equals(q)) return 1;
    if (symbol.startsWith(q)) return 2;
    if (name.startsWith(q)) return 3;
    if (address.equals(q)) return 4;
    if (symbol.contains(q)) return 5;
    if (name.contains(q)) return 6;
    if (address.contains(q)) return 7;
    return 99;
  }

  private static boolean matches(TokenSearchItem item, String query) {
    if (query == null || query.isBlank()) return true;
    String q = query.toLowerCase(Locale.ROOT);
    return lower(item.symbol()).contains(q) || lower(item.name()).contains(q) || lower(item.address()).contains(q);
  }

  private static int confidenceRank(String confidence) {
    return switch (confidence == null ? "" : confidence) {
      case "default" -> 0;
      case "curated" -> 1;
      case "dex-list" -> 2;
      case "contract" -> 3;
      default -> 9;
    };
  }

  private List<TokenSearchItem> defaultTokens(int chainId) {
    return DEFAULT_TOKENS.stream().filter(item -> item.chainId() == chainId).toList();
  }

  private static String normalizeQuery(String query) {
    return query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
  }

  private static int clampLimit(int limit) {
    if (limit <= 0) return 30;
    return Math.min(100, Math.max(1, limit));
  }

  private static String cacheKey(int chainId) {
    return CACHE_PREFIX + chainId;
  }

  private static String addressKey(int chainId, String address) {
    String value = address == null ? "" : address.trim();
    if (value.isBlank()) return "";
    if (chainId == 501 || chainId == 195) return chainId + ":" + value;
    return chainId + ":" + value.toLowerCase(Locale.ROOT);
  }

  private static boolean isValidAddressForChain(int chainId, String address) {
    if (chainId == 195 || chainId == 501) return address != null && !address.trim().isBlank();
    return isLikelyEvmAddress(address);
  }

  private static boolean isLikelyEvmAddress(String address) {
    return address != null && address.trim().matches("^0x[0-9a-fA-F]{40}$");
  }

  private static String standardForChain(int chainId) {
    if (chainId == 195) return "trc20";
    if (chainId == 501) return "spl";
    return "erc20";
  }

  private static String normalizeBaseUrl(String raw) {
    if (raw == null) return "";
    String out = raw.trim();
    while (out.endsWith("/")) out = out.substring(0, out.length() - 1);
    return out;
  }

  private static String normalizeLogoUri(String raw) {
    String uri = textOrNull(raw);
    if (uri == null) return null;
    String assetsPrefix = "https://assets-cdn.trustwallet.com/";
    String rawPrefix = "https://raw.githubusercontent.com/trustwallet/assets/master/";
    String cdnPrefix = "https://cdn.jsdelivr.net/gh/trustwallet/assets@master/";
    if (uri.startsWith("ipfs://")) return "https://cloudflare-ipfs.com/ipfs/" + uri.substring(7);
    if (uri.startsWith(assetsPrefix)) return cdnPrefix + uri.substring(assetsPrefix.length());
    if (uri.startsWith(rawPrefix)) return cdnPrefix + uri.substring(rawPrefix.length());
    return uri;
  }

  private static String textOrNull(String raw) {
    if (raw == null) return null;
    String trimmed = raw.trim();
    return trimmed.isBlank() ? null : trimmed;
  }

  private static String lower(String raw) {
    return raw == null ? "" : raw.toLowerCase(Locale.ROOT);
  }

  private static String upper(String raw) {
    return raw == null ? "" : raw.toUpperCase(Locale.ROOT);
  }

  private static String shortAddress(String address) {
    String value = address == null ? "" : address.trim();
    if (value.length() <= 14) return value;
    return value.substring(0, 6) + "..." + value.substring(value.length() - 4);
  }

  private static String scrubUrl(String url) {
    if (url == null) return "";
    int v2 = url.indexOf("/v2/");
    if (v2 >= 0) return url.substring(0, v2 + 4) + "***";
    return url;
  }

  private static Set<Integer> parseChainIds(String raw) {
    Set<Integer> out = new HashSet<>();
    if (raw == null || raw.isBlank()) return out;
    for (String part : raw.split(",")) {
      try {
        int id = Integer.parseInt(part.trim());
        if (id > 0) out.add(id);
      } catch (NumberFormatException ignored) {
        // ignore
      }
    }
    return out;
  }

  private record CatalogSnapshot(long fetchedAt, boolean stale, List<TokenSearchItem> items) {}

  private record CachedCatalog(int chainId, long fetchedAt, List<TokenSearchItem> items) {}

  private static final class MutableToken {
    private final int chainId;
    private final String address;
    private final String standard;
    private String symbol;
    private String name;
    private int decimals;
    private String logoURI;
    private String confidence;
    private final LinkedHashSet<String> sources = new LinkedHashSet<>();

    private MutableToken(TokenSearchItem item) {
      this.chainId = item.chainId();
      this.address = item.address();
      this.standard = item.standard();
      this.symbol = item.symbol();
      this.name = item.name();
      this.decimals = item.decimals();
      this.logoURI = item.logoURI();
      this.confidence = item.confidence();
      this.sources.addAll(item.sources() == null ? List.of() : item.sources());
    }

    static MutableToken from(TokenSearchItem item) {
      return new MutableToken(item);
    }

    void merge(TokenSearchItem item) {
      List<String> nextSources = item.sources() == null ? List.of() : item.sources();
      sources.addAll(nextSources);
      boolean incomingDefault = nextSources.contains(SOURCE_DEFAULT);
      boolean incomingCurated = "curated".equals(item.confidence());
      boolean currentDefault = "default".equals(confidence);
      if (incomingDefault || (!currentDefault && incomingCurated)) {
        if (textOrNull(item.symbol()) != null) symbol = item.symbol();
        if (textOrNull(item.name()) != null) name = item.name();
        if (item.decimals() >= 0) decimals = item.decimals();
        if (textOrNull(item.logoURI()) != null) logoURI = item.logoURI();
        confidence = incomingDefault ? "default" : "curated";
        return;
      }
      if (textOrNull(name) == null && textOrNull(item.name()) != null) name = item.name();
      if (textOrNull(logoURI) == null && textOrNull(item.logoURI()) != null) logoURI = item.logoURI();
      if (confidenceRank(item.confidence()) < confidenceRank(confidence)) confidence = item.confidence();
    }

    TokenSearchItem toItem() {
      return new TokenSearchItem(
          chainId,
          address,
          standard,
          symbol,
          name,
          decimals,
          logoURI,
          new ArrayList<>(sources),
          confidence);
    }
  }

  private static TokenSearchItem token(
      int chainId,
      String address,
      String standard,
      String symbol,
      String name,
      int decimals,
      String logoURI) {
    return new TokenSearchItem(
        chainId, address, standard, symbol, name, decimals, logoURI, List.of(SOURCE_DEFAULT), "default");
  }

  private static final List<TokenSearchItem> DEFAULT_TOKENS =
      List.of(
          token(1, "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", "erc20", "USDC", "USD Coin", 6, null),
          token(1, "0xdAC17F958D2ee523a2206206994597C13D831ec7", "erc20", "USDT", "Tether USD", 6, null),
          token(1, "0x6B175474E89094C44Da98b954EedeAC495271d0F", "erc20", "DAI", "Dai Stablecoin", 18, null),
          token(1, "0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2", "erc20", "WETH", "Wrapped Ether", 18, null),
          token(8453, "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913", "erc20", "USDC", "USD Coin", 6, null),
          token(8453, "0x4200000000000000000000000000000000000006", "erc20", "WETH", "Wrapped Ether", 18, null),
          token(10, "0x0b2C639c533813f4Aa9D7837CAf62653d097Ff85", "erc20", "USDC", "USD Coin", 6, null),
          token(10, "0x94b008aA00579c1307B0EF2c499aD98a8ce58e58", "erc20", "USDT", "Tether USD", 6, null),
          token(10, "0xDA10009cBd5D07dd0CeCc66161FC93D7c9000da1", "erc20", "DAI", "Dai Stablecoin", 18, null),
          token(10, "0x4200000000000000000000000000000000000006", "erc20", "WETH", "Wrapped Ether", 18, null),
          token(42161, "0xaf88d065e77c8cC2239327C5EDb3A432268e5831", "erc20", "USDC", "USD Coin", 6, null),
          token(42161, "0xFd086bC7CD5C481DCC9C85ebE478A1C0b69FCbb9", "erc20", "USDT", "Tether USD", 6, null),
          token(42161, "0xDA10009cBd5D07dd0CeCc66161FC93D7c9000da1", "erc20", "DAI", "Dai Stablecoin", 18, null),
          token(42161, "0x82aF49447D8a07e3bd95BD0d56f35241523fBab1", "erc20", "WETH", "Wrapped Ether", 18, null),
          token(56, "0x8AC76a51cc950d9822D68b83fE1Ad97B32Cd580d", "erc20", "USDC", "USD Coin", 18, null),
          token(56, "0x55d398326f99059fF775485246999027B3197955", "erc20", "USDT", "Tether USD", 18, null),
          token(56, "0xbb4CdB9CBd36B01bD1cBaEBF2De08d9173bc095c", "erc20", "WBNB", "Wrapped BNB", 18, null),
          token(56, "0x9C79B4a12eF8176B6a61142408A54Edf8336b0A6", "erc20", "VEILX", "VEILX", 18, "asset://walletPage/veilx_token_icon"),
          token(56, "0x796B08f7BA8d1859Ea4B9FBFECe57D06A1b49F88", "erc20", "VIPL", "VeilPlus", 18, "asset://walletPage/vipl_token_icon"),
          token(196, "0x74b7F16337B8972027f6196a17A631ac6DE26d22", "erc20", "USDC", "USD Coin", 6, "asset://token/usdc"),
          token(195, "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", "trc20", "USDT", "Tether USD", 6, null));
}
