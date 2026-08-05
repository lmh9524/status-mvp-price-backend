package io.statusmvp.pricebackend.client;

import com.fasterxml.jackson.databind.JsonNode;
import io.statusmvp.pricebackend.model.PriceMarketData;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class DexScreenerClient {
  private static final Logger log = LoggerFactory.getLogger(DexScreenerClient.class);
  private static final int MAX_ADDRESSES_PER_REQUEST = 30;
  private static final Map<Integer, String> CHAIN_SLUGS =
      Map.of(
          1, "ethereum",
          10, "optimism",
          56, "bsc",
          137, "polygon",
          8453, "base",
          42161, "arbitrum");

  private final WebClient webClient;
  private final boolean enabled;
  private final String baseUrl;

  public DexScreenerClient(
      WebClient webClient,
      @Value("${app.dexscreener.enabled:true}") boolean enabled,
      @Value("${app.dexscreener.baseUrl:https://api.dexscreener.com}") String baseUrl) {
    this.webClient = webClient;
    this.enabled = enabled;
    this.baseUrl = normalizeBaseUrl(baseUrl);
  }

  public boolean isEnabled() {
    return enabled && !baseUrl.isBlank();
  }

  public Map<String, PriceMarketData> fetchTokenQuotesByContract(
      int chainId, List<String> contractAddresses) {
    Map<String, PriceMarketData> out = new HashMap<>();
    String chainSlug = CHAIN_SLUGS.get(chainId);
    if (!isEnabled() || chainSlug == null || contractAddresses == null || contractAddresses.isEmpty()) {
      return out;
    }

    List<String> addresses =
        contractAddresses.stream()
            .map(address -> normalizeAddressKey(chainId, address))
            .filter(address -> !address.isBlank())
            .distinct()
            .toList();
    if (addresses.isEmpty()) return out;

    for (List<String> chunk : chunks(addresses, MAX_ADDRESSES_PER_REQUEST)) {
      fetchChunk(chainId, chainSlug, chunk, out);
    }
    return out;
  }

  private void fetchChunk(
      int chainId, String chainSlug, List<String> addresses, Map<String, PriceMarketData> out) {
    String csv = String.join(",", addresses);
    Set<String> requested = Set.copyOf(addresses);
    Map<String, Double> liquidityByAddress = new HashMap<>();
    URI uri =
        UriComponentsBuilder.fromUriString(baseUrl + "/tokens/v1/" + chainSlug + "/" + csv)
            .build(true)
            .toUri();

    try {
      JsonNode root =
          webClient
              .get()
              .uri(uri)
              .retrieve()
              .bodyToMono(JsonNode.class)
              .timeout(Duration.ofSeconds(10))
              .block();

      if (root == null || !root.isArray()) {
        log.warn("DEX Screener returned invalid body for chainId={} addresses='{}' body={}", chainId, csv, root);
        return;
      }

      for (JsonNode pair : root) {
        String tokenAddress = resolveRequestedTokenAddress(chainId, pair, requested);
        if (tokenAddress.isBlank()) continue;

        Double price = parseMaybeDouble(pair.path("priceUsd"));
        if (price == null || price <= 0d) continue;

        Double liquidityUsd = parseMaybeDouble(pair.path("liquidity").path("usd"));
        PriceMarketData incoming =
            new PriceMarketData(price, parseMaybeDouble(pair.path("priceChange").path("h24")));
        PriceMarketData existing = out.get(tokenAddress);
        Double existingLiquidityUsd = liquidityByAddress.get(tokenAddress);
        if (existing == null || isBetterQuote(liquidityUsd, existingLiquidityUsd)) {
          out.put(tokenAddress, incoming);
          liquidityByAddress.put(tokenAddress, liquidityUsd == null ? 0d : liquidityUsd);
        }
      }
    } catch (Exception e) {
      log.warn("DEX Screener token quote request failed for chainId={} addresses='{}' uri={}", chainId, csv, uri, e);
    }
  }

  private static boolean isBetterQuote(Double incomingLiquidityUsd, Double existingLiquidityUsd) {
    if (incomingLiquidityUsd == null) return false;
    return existingLiquidityUsd == null || incomingLiquidityUsd > existingLiquidityUsd;
  }

  private static String resolveRequestedTokenAddress(int chainId, JsonNode pair, Set<String> requested) {
    String base = normalizeAddressKey(chainId, pair.path("baseToken").path("address").asText(null));
    if (requested.contains(base)) return base;

    String quote = normalizeAddressKey(chainId, pair.path("quoteToken").path("address").asText(null));
    if (requested.contains(quote)) return quote;

    return "";
  }

  private static List<List<String>> chunks(List<String> values, int size) {
    List<List<String>> out = new ArrayList<>();
    for (int i = 0; i < values.size(); i += size) {
      out.add(values.subList(i, Math.min(values.size(), i + size)));
    }
    return out;
  }

  private static Double parseMaybeDouble(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) return null;
    try {
      if (node.isNumber()) return node.asDouble();
      String s = node.asText(null);
      if (s == null || s.isBlank()) return null;
      double v = Double.parseDouble(s.trim());
      return Double.isFinite(v) ? v : null;
    } catch (Exception ignored) {
      return null;
    }
  }

  private static String normalizeAddressKey(int chainId, String raw) {
    if (raw == null) return "";
    String value = raw.trim();
    if (value.isBlank()) return "";
    if (chainId == 195 || chainId == 501) return value;
    if (value.startsWith("0x") && value.length() >= 42) {
      return value.toLowerCase(Locale.ROOT);
    }
    return "";
  }

  private static String normalizeBaseUrl(String raw) {
    if (raw == null) return "";
    String value = raw.trim();
    while (value.endsWith("/")) {
      value = value.substring(0, value.length() - 1);
    }
    return value;
  }
}
