package io.statusmvp.pricebackend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.statusmvp.pricebackend.model.PortfolioAssetSnapshotV2;
import io.statusmvp.pricebackend.model.PortfolioChainSummary;
import io.statusmvp.pricebackend.model.PortfolioSnapshot;
import io.statusmvp.pricebackend.model.PortfolioSnapshotV2;
import io.statusmvp.pricebackend.model.PriceQuote;
import io.statusmvp.pricebackend.util.PriceMappings;
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
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Keys;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;

@Service
public class PortfolioAggregatorService {
  private static final Logger log = LoggerFactory.getLogger(PortfolioAggregatorService.class);

  private static final Pattern EVM_ADDRESS = Pattern.compile("^0x[0-9a-fA-F]{40}$");
  private static final Pattern STABLE_WITH_SUFFIX_DIGITS =
      Pattern.compile("^(USDC|USDT|DAI|BUSD|TUSD|USDP|GUSD|FRAX|LUSD|SUSD|USDD|USDG|PYUSD|FDUSD|USDE)\\d+$");
  private static final int V1_SUMMARY_SNAPSHOT_LIMIT = 1000;

  private static final Map<Integer, ChainMeta> SUPPORTED_CHAINS =
      Map.of(
          1, new ChainMeta("eth", "ETH"),
          10, new ChainMeta("optimism", "ETH"),
          56, new ChainMeta("bsc", "BNB"),
          8453, new ChainMeta("base", "ETH"),
          42161, new ChainMeta("arbitrum", "ETH"));

  // Best-effort: if upstream doesn't provide thumbnails, fall back to TrustWallet's public assets repo.
  // Client still needs to handle 404 (not all assets exist).
  private static final String TRUSTWALLET_CDN_PREFIX =
      "https://cdn.jsdelivr.net/gh/trustwallet/assets@master/blockchains/";
  private static final Map<Integer, String> TRUSTWALLET_CHAIN_SLUG_BY_ID =
      Map.of(
          1, "ethereum",
          10, "optimism",
          56, "smartchain",
          8453, "base",
          42161, "arbitrum");

  private static String buildTrustWalletChainLogoUrl(Integer chainId) {
    if (chainId == null) return null;
    String slug = TRUSTWALLET_CHAIN_SLUG_BY_ID.get(chainId);
    if (slug == null || slug.isBlank()) return null;
    return TRUSTWALLET_CDN_PREFIX + slug + "/info/logo.png";
  }

  private static String buildTrustWalletAssetLogoUrl(Integer chainId, String contract) {
    if (chainId == null) return null;
    String slug = TRUSTWALLET_CHAIN_SLUG_BY_ID.get(chainId);
    if (slug == null || slug.isBlank()) return null;
    String addr = contract == null ? "" : contract.trim();
    if (!EVM_ADDRESS.matcher(addr).matches()) return null;
    try {
      String checksum = Keys.toChecksumAddress(addr);
      return TRUSTWALLET_CDN_PREFIX + slug + "/assets/" + checksum + "/logo.png";
    } catch (Exception ignored) {
      return null;
    }
  }

  private final WebClient webClient;
  private final RedisCache cache;
  private final ObjectMapper mapper = new ObjectMapper();

  private final Optional<Web3j> bscWeb3j;
  private final VeilxDexPriceService veilxDex;
  private final PriceAggregatorService priceAggregator;

  private final String ankrBaseUrl;
  private final String ankrApiKey;
  private final long requestTtlSeconds;
  private final Duration timeout;
  private final List<Integer> defaultChainIds;

  public PortfolioAggregatorService(
      WebClient webClient,
      RedisCache cache,
      ObjectProvider<Web3j> bscWeb3jProvider,
      VeilxDexPriceService veilxDex,
      PriceAggregatorService priceAggregator,
      @Value("${app.portfolio.ankrBaseUrl:https://rpc.ankr.com/multichain}") String ankrBaseUrl,
      @Value(
              "${app.portfolio.ankrApiKey:8ab456e0616fa58794745f952b83f719e7ea3af2d0c9cbac69b8f22323563de7}")
          String ankrApiKey,
      @Value("${app.portfolio.requestTtlSeconds:30}") long requestTtlSeconds,
      @Value("${app.portfolio.timeoutMs:12000}") long timeoutMs,
      @Value("${app.portfolio.defaultChainIds:1,10,56,8453,42161}") String defaultChainIds) {
    this.webClient = webClient;
    this.cache = cache;
    this.bscWeb3j = Optional.ofNullable(bscWeb3jProvider.getIfAvailable());
    this.veilxDex = veilxDex;
    this.priceAggregator = priceAggregator;
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

    snapshot = augmentSnapshotV2WithVeilTokens(snapshot, chainIds, filter);

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

    for (JsonNode asset : assets) {
      String blockchain = normalizeBlankToNull(asset.path("blockchain").asText(null));
      if (blockchain == null) continue;
      Integer chainId = chainIdByBlockchain.get(blockchain.toLowerCase(Locale.ROOT));
      if (chainId == null || !chainIds.contains(chainId)) continue;

      String tokenType = normalizeBlankToNull(asset.path("tokenType").asText(null));
      boolean isNative =
          asset.path("isNative").asBoolean(false)
              || (tokenType != null && tokenType.trim().equalsIgnoreCase("native"));
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
      if (logoUrl == null) {
        logoUrl = isNative ? buildTrustWalletChainLogoUrl(chainId) : buildTrustWalletAssetLogoUrl(chainId, contract);
      }
      Long blockNumber = parseLong(asset.path("blockHeight"));
      if (blockNumber != null) {
        Long prev = blockNumbersByChainId.get(chainId);
        if (prev == null || blockNumber > prev) {
          blockNumbersByChainId.put(chainId, blockNumber);
        }
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

    out = backfillMissingUsdData(out, currency);

    Map<String, Double> fallbackUsdByAssetKey = new HashMap<>();
    for (PortfolioAssetSnapshotV2 asset : out) {
      if (asset == null) continue;
      Double usdValue = positiveOrNull(asset.usdValue());
      if (usdValue == null) continue;
      String key;
      if (asset.isNative()) {
        key = asset.chainId() + ":native";
      } else {
        String contract = normalizeBlankToNull(asset.contractAddress());
        if (contract == null) continue;
        key = asset.chainId() + ":" + contract.toLowerCase(Locale.ROOT);
      }
      Double prev = fallbackUsdByAssetKey.get(key);
      if (prev == null || usdValue > prev) {
        fallbackUsdByAssetKey.put(key, usdValue);
      }
    }

    double fallbackTotal = 0d;
    for (Double v : fallbackUsdByAssetKey.values()) {
      if (v != null && Double.isFinite(v) && v > 0) {
        fallbackTotal += v;
      }
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

    double finalTotal;
    if (totalUsd != null && Double.isFinite(totalUsd) && totalUsd >= 0d) {
      double upstreamRounded = round2(totalUsd);
      double fallbackRounded = round2(fallbackTotal);
      finalTotal = Math.max(upstreamRounded, fallbackRounded);
    } else {
      finalTotal = round2(fallbackTotal);
    }
    return Optional.of(
        new PortfolioSnapshotV2(
            address,
            now,
            currency,
            finalTotal,
            Map.copyOf(blockNumbersByChainId),
            out));
  }

  List<PortfolioAssetSnapshotV2> backfillMissingUsdData(
      List<PortfolioAssetSnapshotV2> assets, String currency) {
    if (assets == null || assets.isEmpty()) return assets == null ? List.of() : assets;
    if (priceAggregator == null || !"usd".equals(normalizeCurrency(currency))) {
      return assets;
    }

    Map<Integer, List<String>> contractAddressesByChainId = new LinkedHashMap<>();
    List<String> symbolFallbacks = new ArrayList<>();

    for (PortfolioAssetSnapshotV2 asset : assets) {
      if (asset == null) continue;
      if (positiveOrNull(asset.usdPrice()) != null && positiveOrNull(asset.usdValue()) != null) {
        continue;
      }

      if (!asset.isNative()) {
        String contract = normalizeBlankToNull(asset.contractAddress());
        if (contract != null) {
          String contractLower = contract.toLowerCase(Locale.ROOT);
          List<String> list =
              contractAddressesByChainId.computeIfAbsent(asset.chainId(), ignored -> new ArrayList<>());
          if (!list.contains(contractLower)) {
            list.add(contractLower);
          }
        }
      }

      String symbolKey = resolveSymbolFallbackKey(asset);
      if (symbolKey != null && !symbolFallbacks.contains(symbolKey)) {
        symbolFallbacks.add(symbolKey);
      }
    }

    Map<String, Double> usdPriceByChainContract = new HashMap<>();
    for (Map.Entry<Integer, List<String>> entry : contractAddressesByChainId.entrySet()) {
      Integer chainId = entry.getKey();
      List<String> contracts = entry.getValue();
      if (chainId == null || contracts == null || contracts.isEmpty()) continue;
      try {
        List<PriceQuote> quotes = priceAggregator.getPricesByContract(chainId, contracts, "usd");
        for (PriceQuote quote : quotes) {
          if (quote == null) continue;
          String contract = normalizeBlankToNull(quote.contractAddress());
          Double price = positiveOrNull(quote.price());
          if (contract == null || price == null) continue;
          usdPriceByChainContract.put(
              chainId + ":" + contract.toLowerCase(Locale.ROOT), price);
        }
      } catch (Exception e) {
        log.warn("portfolio snapshot contract fallback pricing failed: chainId={}", chainId, e);
      }
    }

    Map<String, Double> usdPriceBySymbol = new HashMap<>();
    if (!symbolFallbacks.isEmpty()) {
      try {
        List<PriceQuote> quotes = priceAggregator.getPrices(symbolFallbacks, "usd");
        for (PriceQuote quote : quotes) {
          if (quote == null) continue;
          String symbolKey = normalizeUsdLookupSymbol(quote.symbol());
          Double price = positiveOrNull(quote.price());
          if (symbolKey == null || price == null) continue;
          usdPriceBySymbol.put(symbolKey, price);
        }
      } catch (Exception e) {
        log.warn("portfolio snapshot symbol fallback pricing failed", e);
      }
    }

    boolean changed = false;
    List<PortfolioAssetSnapshotV2> out = new ArrayList<>(assets.size());
    for (PortfolioAssetSnapshotV2 asset : assets) {
      if (asset == null) continue;

      Double usdPrice = positiveOrNull(asset.usdPrice());
      Double usdValue = positiveOrNull(asset.usdValue());

      if (usdPrice == null && !asset.isNative()) {
        String contract = normalizeBlankToNull(asset.contractAddress());
        if (contract != null) {
          usdPrice =
              positiveOrNull(
                  usdPriceByChainContract.get(
                      asset.chainId() + ":" + contract.toLowerCase(Locale.ROOT)));
        }
      }

      if (usdPrice == null) {
        String symbolKey = resolveSymbolFallbackKey(asset);
        if (symbolKey != null) {
          usdPrice = positiveOrNull(usdPriceBySymbol.get(symbolKey));
        }
      }

      if (usdValue == null && usdPrice != null) {
        usdValue = computeUsdValue(asset.balance(), usdPrice);
      }

      Double originalUsdPrice = positiveOrNull(asset.usdPrice());
      Double originalUsdValue = positiveOrNull(asset.usdValue());
      if (
          equalsNullableDouble(originalUsdPrice, usdPrice)
              && equalsNullableDouble(originalUsdValue, usdValue)) {
        out.add(asset);
        continue;
      }

      changed = true;
      out.add(
          new PortfolioAssetSnapshotV2(
              asset.chainId(),
              asset.blockchain(),
              asset.isNative(),
              asset.contractAddress(),
              asset.symbol(),
              asset.name(),
              asset.decimals(),
              asset.balanceRaw(),
              asset.balance(),
              usdPrice,
              usdValue,
              asset.logoUrl(),
              asset.blockNumber()));
    }

    return changed ? out : assets;
  }

  private PortfolioSnapshotV2 augmentSnapshotV2WithVeilTokens(
      PortfolioSnapshotV2 snapshot, List<Integer> chainIds, SnapshotFilter filter) {
    if (snapshot == null || snapshot.assets() == null || chainIds == null) return snapshot;
    if (!chainIds.contains(56)) return snapshot;
    if (bscWeb3j.isEmpty()) return snapshot;

    String veilxAddr = normalizeBlankToNull(veilxDex != null ? veilxDex.veilxContractLower() : null);
    String viplAddr = normalizeBlankToNull(veilxDex != null ? veilxDex.viplContractLower() : null);
    if ((veilxAddr == null || veilxAddr.isBlank()) && (viplAddr == null || viplAddr.isBlank())) {
      return snapshot;
    }

    Map<String, Integer> idxByContractLower = new HashMap<>();
    List<PortfolioAssetSnapshotV2> nextAssets = new ArrayList<>();
    for (PortfolioAssetSnapshotV2 a : snapshot.assets()) {
      if (a != null) {
        nextAssets.add(a);
        if (a.chainId() == 56 && !a.isNative()) {
          String c = normalizeBlankToNull(a.contractAddress());
          if (c != null) {
            idxByContractLower.put(c.toLowerCase(Locale.ROOT), nextAssets.size() - 1);
          }
        }
      }
    }

    addOrUpdateBscToken(
        nextAssets,
        idxByContractLower,
        snapshot.address(),
        filter,
        veilxAddr,
        "VEILX",
        "VEILX",
        () -> veilxDex != null ? veilxDex.fetchVeilxUsdPrice().orElse(null) : null);

    addOrUpdateBscToken(
        nextAssets,
        idxByContractLower,
        snapshot.address(),
        filter,
        viplAddr,
        "VIPL",
        "VeilPlus",
        () -> veilxDex != null ? veilxDex.fetchViplUsdPrice().orElse(null) : null);

    nextAssets.sort(
        (a, b) -> {
          double av = a != null && a.usdValue() != null ? a.usdValue() : 0d;
          double bv = b != null && b.usdValue() != null ? b.usdValue() : 0d;
          int cmp = Double.compare(bv, av);
          if (cmp != 0) return cmp;
          String as = a != null ? a.symbol() : "";
          String bs = b != null ? b.symbol() : "";
          return as.compareToIgnoreCase(bs);
        });
    if (filter != null && nextAssets.size() > filter.limit()) {
      nextAssets = new ArrayList<>(nextAssets.subList(0, filter.limit()));
    }

    Double nextTotalUsd = snapshot.totalUsd();
    double currentTotal = nextTotalUsd != null && Double.isFinite(nextTotalUsd) ? nextTotalUsd : 0d;
    double sumUsd = 0d;
    for (PortfolioAssetSnapshotV2 a : nextAssets) {
      if (a == null) continue;
      Double v = positiveOrNull(a.usdValue());
      if (v != null) sumUsd += v;
    }
    if (sumUsd > currentTotal) {
      nextTotalUsd = round2(sumUsd);
    }

    return new PortfolioSnapshotV2(
        snapshot.address(),
        snapshot.fetchedAt(),
        snapshot.currency(),
        nextTotalUsd,
        snapshot.blockNumbersByChainId(),
        nextAssets);
  }

  private void addOrUpdateBscToken(
      List<PortfolioAssetSnapshotV2> assets,
      Map<String, Integer> idxByContractLower,
      String walletAddress,
      SnapshotFilter filter,
      String contractLower,
      String symbol,
      String name,
      Supplier<Double> usdPriceSupplier) {
    if (assets == null || idxByContractLower == null) return;
    if (walletAddress == null || walletAddress.isBlank()) return;
    String contract = normalizeBlankToNull(contractLower);
    if (contract == null) return;
    if (!EVM_ADDRESS.matcher(contract).matches()) return;

    BigInteger raw =
        fetchBscErc20BalanceRaw(contract, walletAddress).orElse(null);
    if (raw == null) return;
    if (!filter.includeZero() && raw.signum() <= 0) return;

    Double usdPrice = usdPriceSupplier != null ? usdPriceSupplier.get() : null;

    final int decimals = 18;
    String balance = formatUnits(raw, decimals);
    Double usdValue = null;
    if (usdPrice != null && Double.isFinite(usdPrice) && usdPrice > 0 && balance != null) {
      try {
        BigDecimal b = new BigDecimal(balance);
        BigDecimal p = BigDecimal.valueOf(usdPrice);
        BigDecimal v = b.multiply(p);
        usdValue = v.doubleValue();
      } catch (Exception ignored) {
        usdValue = null;
      }
    }

    if (usdValue != null && usdValue > 0 && usdValue < filter.minUsd()) {
      return;
    }

    String logoUrl = buildTrustWalletAssetLogoUrl(56, contract);
    if (logoUrl == null) {
      logoUrl = buildTrustWalletChainLogoUrl(56);
    }

    PortfolioAssetSnapshotV2 next =
        new PortfolioAssetSnapshotV2(
            56,
            "bsc",
            false,
            contract,
            symbol,
            name,
            decimals,
            raw.toString(),
            balance,
            usdPrice,
            usdValue,
            logoUrl,
            null);

    Integer idx = idxByContractLower.get(contract.toLowerCase(Locale.ROOT));
    if (idx != null && idx >= 0 && idx < assets.size()) {
      assets.set(idx, next);
      return;
    }

    idxByContractLower.put(contract.toLowerCase(Locale.ROOT), assets.size());
    assets.add(next);
  }

  private Optional<BigInteger> fetchBscErc20BalanceRaw(String tokenContract, String owner) {
    if (bscWeb3j.isEmpty()) return Optional.empty();
    String contract = normalizeBlankToNull(tokenContract);
    String address = normalizeBlankToNull(owner);
    if (contract == null || address == null) return Optional.empty();
    if (!EVM_ADDRESS.matcher(contract).matches()) return Optional.empty();
    if (!EVM_ADDRESS.matcher(address).matches()) return Optional.empty();

    try {
      Function fn =
          new Function(
              "balanceOf",
              List.of(new Address(address)),
              List.of(new TypeReference<Uint256>() {}));
      String data = FunctionEncoder.encode(fn);
      Transaction tx = Transaction.createEthCallTransaction(null, contract, data);
      EthCall resp =
          bscWeb3j.get().ethCall(tx, DefaultBlockParameterName.LATEST).send();
      if (resp == null || resp.hasError()) {
        return Optional.empty();
      }
      @SuppressWarnings("rawtypes")
      List<Type> decoded =
          FunctionReturnDecoder.decode(resp.getValue(), fn.getOutputParameters());
      if (decoded == null || decoded.isEmpty()) return Optional.empty();
      Uint256 v = (Uint256) decoded.get(0);
      return Optional.ofNullable(v.getValue());
    } catch (Exception e) {
      log.warn(
          "bsc erc20 balanceOf failed: token={} address={}", tokenContract, owner, e);
      return Optional.empty();
    }
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

  private static boolean equalsNullableDouble(Double left, Double right) {
    if (left == null || right == null) return left == right;
    return Double.compare(left, right) == 0;
  }

  private static Double computeUsdValue(String balance, Double usdPrice) {
    Double price = positiveOrNull(usdPrice);
    String normalizedBalance = normalizeBlankToNull(balance);
    if (price == null || normalizedBalance == null) return null;
    try {
      BigDecimal units = new BigDecimal(normalizedBalance);
      if (units.signum() <= 0) return null;
      return positiveOrNull(units.multiply(BigDecimal.valueOf(price)).doubleValue());
    } catch (Exception ignored) {
      return null;
    }
  }

  private static String resolveSymbolFallbackKey(PortfolioAssetSnapshotV2 asset) {
    if (asset == null) return null;
    String symbolKey = normalizeUsdLookupSymbol(asset.symbol());
    if (symbolKey == null) return null;
    if (asset.isNative()) return symbolKey;
    return PriceMappings.STABLECOINS.contains(symbolKey) ? symbolKey : null;
  }

  private static String normalizeUsdLookupSymbol(String raw) {
    String normalized = normalizeBlankToNull(raw);
    if (normalized == null) return null;
    String symbol = normalized.toUpperCase(Locale.ROOT);
    if (symbol.endsWith(".E")) {
      symbol = symbol.substring(0, symbol.length() - 2);
    }
    if (symbol.indexOf('₮') >= 0) {
      symbol = symbol.replace("₮", "T");
    }
    if (STABLE_WITH_SUFFIX_DIGITS.matcher(symbol).matches()) {
      symbol = symbol.replaceAll("\\d+$", "");
    }
    return symbol.isBlank() ? null : symbol;
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
