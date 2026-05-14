package io.statusmvp.pricebackend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.statusmvp.pricebackend.client.SafeTxServiceClient;
import io.statusmvp.pricebackend.model.PortfolioAssetSnapshotV2;
import io.statusmvp.pricebackend.model.PortfolioSnapshotV2;
import io.statusmvp.pricebackend.model.safe.SafeAssetBalanceItem;
import io.statusmvp.pricebackend.model.safe.SafeAssetBalanceToken;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class SafeAssetBalanceService {
  private static final Pattern EVM_ADDRESS = Pattern.compile("^0x[a-fA-F0-9]{40}$");
  private static final Map<Integer, String> SAFE_CHAIN_CODES =
      Map.of(
          1, "eth",
          10, "oeth",
          56, "bnb",
          8453, "base",
          42161, "arb1");
  private static final Set<String> DEFAULT_VISIBLE_TOKEN_KEYS =
      Set.of(
          "56:0x75999a8ee8f52666c4d76f6d0f8e7e914334762a",
          "56:0x796b08f7ba8d1859ea4b9fbfece57d06a1b49f88");

  private final SafeTxServiceClient safeTxService;
  private final PortfolioAggregatorService portfolio;
  private final ObjectMapper objectMapper;

  public SafeAssetBalanceService(
      SafeTxServiceClient safeTxService,
      PortfolioAggregatorService portfolio,
      ObjectMapper objectMapper) {
    this.safeTxService = safeTxService;
    this.portfolio = portfolio;
    this.objectMapper = objectMapper;
  }

  public Mono<List<SafeAssetBalanceItem>> listBalances(
      int chainId, String safeAddress, boolean trustedOnly) {
    String safe = normalizeAddress(safeAddress);
    if (safe == null) return Mono.just(List.of());

    Mono<List<SafeAssetBalanceItem>> safeBalances =
        fetchSafeTxServiceBalances(chainId, safe, trustedOnly);
    Mono<List<SafeAssetBalanceItem>> portfolioBalances =
        Mono.fromCallable(() -> fetchPortfolioBalances(chainId, safe, trustedOnly))
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorReturn(List.of());

    return Mono.zip(safeBalances, portfolioBalances)
        .map(tuple -> mergeBalances(tuple.getT1(), tuple.getT2()));
  }

  private Mono<List<SafeAssetBalanceItem>> fetchSafeTxServiceBalances(
      int chainId, String safeAddress, boolean trustedOnly) {
    String chainCode = SAFE_CHAIN_CODES.get(chainId);
    if (chainCode == null) return Mono.just(List.of());

    MultiValueMap<String, String> query = new LinkedMultiValueMap<>();
    if (trustedOnly) query.add("trusted", "true");

    return safeTxService
        .get(chainCode, "/api/v1/safes/" + safeAddress + "/balances/", query)
        .map(this::parseSafeBalances)
        .onErrorReturn(List.of());
  }

  private List<SafeAssetBalanceItem> parseSafeBalances(ResponseEntity<String> response) {
    if (response == null || !response.getStatusCode().is2xxSuccessful()) return List.of();
    String body = response.getBody();
    if (body == null || body.isBlank()) return List.of();
    try {
      JsonNode root = objectMapper.readTree(body);
      if (!root.isArray()) return List.of();
      List<SafeAssetBalanceItem> out = new ArrayList<>();
      for (JsonNode item : root) {
        String balance = textOrNull(item.path("balance"));
        if (balance == null || isZeroBalance(balance)) continue;
        String tokenAddress = normalizeAddress(textOrNull(item.path("tokenAddress")));
        JsonNode tokenNode = item.path("token");
        SafeAssetBalanceToken token = null;
        if (tokenNode != null && tokenNode.isObject()) {
          token =
              new SafeAssetBalanceToken(
                  textOrNull(tokenNode.path("name")),
                  textOrNull(tokenNode.path("symbol")),
                  intOrNull(tokenNode.path("decimals")),
                  textOrNull(tokenNode.path("logoUri")));
        }
        Boolean trusted =
            item.has("trusted") && !item.path("trusted").isNull()
                ? item.path("trusted").asBoolean()
                : null;
        out.add(
            new SafeAssetBalanceItem(
                tokenAddress, token, balance, trusted, List.of("safe-tx-service")));
      }
      return out;
    } catch (Exception ignored) {
      return List.of();
    }
  }

  private List<SafeAssetBalanceItem> fetchPortfolioBalances(
      int chainId, String safeAddress, boolean trustedOnly) {
    PortfolioSnapshotV2 snapshot =
        portfolio.getPortfolioSnapshotV2(
            safeAddress, List.of(chainId), "usd", 0d, false, 1000, true);
    if (snapshot == null || snapshot.assets() == null) return List.of();

    List<SafeAssetBalanceItem> out = new ArrayList<>();
    for (PortfolioAssetSnapshotV2 asset : snapshot.assets()) {
      if (asset == null || asset.chainId() != chainId) continue;
      if (asset.balanceRaw() == null
          || asset.balanceRaw().isBlank()
          || isZeroBalance(asset.balanceRaw())) {
        continue;
      }

      String tokenAddress = asset.isNative() ? null : normalizeAddress(asset.contractAddress());
      if (!asset.isNative() && tokenAddress == null) continue;

      boolean defaultVisible = asset.isNative() || isDefaultVisibleToken(chainId, tokenAddress);
      if (trustedOnly && !defaultVisible) continue;

      SafeAssetBalanceToken token =
          asset.isNative()
              ? null
              : new SafeAssetBalanceToken(
                  asset.name(), asset.symbol(), asset.decimals(), asset.logoUrl());
      out.add(
          new SafeAssetBalanceItem(
              tokenAddress,
              token,
              asset.balanceRaw(),
              defaultVisible ? Boolean.TRUE : Boolean.FALSE,
              List.of("portfolio")));
    }
    return out;
  }

  private List<SafeAssetBalanceItem> mergeBalances(
      List<SafeAssetBalanceItem> safeBalances, List<SafeAssetBalanceItem> portfolioBalances) {
    Map<String, SafeAssetBalanceItem> merged = new LinkedHashMap<>();
    for (SafeAssetBalanceItem item : safeBalances) {
      putMerged(merged, item);
    }
    for (SafeAssetBalanceItem item : portfolioBalances) {
      putMerged(merged, item);
    }
    return merged.values().stream().sorted(SafeAssetBalanceService::compareItems).toList();
  }

  private void putMerged(Map<String, SafeAssetBalanceItem> merged, SafeAssetBalanceItem item) {
    if (item == null || item.balance() == null || isZeroBalance(item.balance())) return;
    String key = item.tokenAddress() == null ? "__native__" : item.tokenAddress().toLowerCase(Locale.ROOT);
    SafeAssetBalanceItem existing = merged.get(key);
    if (existing == null) {
      merged.put(key, item);
      return;
    }

    List<String> sources = new ArrayList<>();
    if (existing.sources() != null) sources.addAll(existing.sources());
    if (item.sources() != null) {
      for (String source : item.sources()) {
        if (source != null && !source.isBlank() && !sources.contains(source)) sources.add(source);
      }
    }
    SafeAssetBalanceToken token = existing.token() != null ? existing.token() : item.token();
    Boolean trusted = Boolean.TRUE.equals(existing.trusted()) || Boolean.TRUE.equals(item.trusted());
    merged.put(
        key,
        new SafeAssetBalanceItem(
            existing.tokenAddress(),
            token,
            existing.balance(),
            trusted,
            sources));
  }

  private static int compareItems(SafeAssetBalanceItem a, SafeAssetBalanceItem b) {
    boolean aNative = a != null && a.tokenAddress() == null;
    boolean bNative = b != null && b.tokenAddress() == null;
    if (aNative != bNative) return aNative ? -1 : 1;
    String as = a != null && a.token() != null ? safeUpper(a.token().symbol()) : "";
    String bs = b != null && b.token() != null ? safeUpper(b.token().symbol()) : "";
    int symbolCompare = Comparator.nullsLast(String::compareTo).compare(as, bs);
    if (symbolCompare != 0) return symbolCompare;
    String aa = a != null && a.tokenAddress() != null ? a.tokenAddress() : "";
    String bb = b != null && b.tokenAddress() != null ? b.tokenAddress() : "";
    return aa.compareTo(bb);
  }

  private static String normalizeAddress(String value) {
    if (value == null) return null;
    String trimmed = value.trim();
    if (!EVM_ADDRESS.matcher(trimmed).matches()) return null;
    return trimmed.toLowerCase(Locale.ROOT);
  }

  private static boolean isDefaultVisibleToken(int chainId, String tokenAddress) {
    if (tokenAddress == null) return false;
    return DEFAULT_VISIBLE_TOKEN_KEYS.contains(chainId + ":" + tokenAddress.toLowerCase(Locale.ROOT));
  }

  private static boolean isZeroBalance(String value) {
    if (value == null) return true;
    String trimmed = value.trim();
    if (trimmed.isBlank()) return true;
    try {
      return new BigInteger(trimmed).signum() <= 0;
    } catch (Exception ignored) {
      try {
        return new BigDecimal(trimmed).signum() <= 0;
      } catch (Exception ignoredAgain) {
        return false;
      }
    }
  }

  private static String textOrNull(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) return null;
    String text = node.asText(null);
    if (text == null) return null;
    String trimmed = text.trim();
    return trimmed.isBlank() ? null : trimmed;
  }

  private static Integer intOrNull(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) return null;
    if (!node.canConvertToInt()) return null;
    return node.asInt();
  }

  private static String safeUpper(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }
}
