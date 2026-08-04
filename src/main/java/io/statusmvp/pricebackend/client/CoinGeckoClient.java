package io.statusmvp.pricebackend.client;

import com.fasterxml.jackson.databind.JsonNode;
import io.statusmvp.pricebackend.model.PriceCandle;
import io.statusmvp.pricebackend.model.PriceMarketData;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class CoinGeckoClient {
  private static final Logger log = LoggerFactory.getLogger(CoinGeckoClient.class);
  private final WebClient webClient;
  private final String apiKey;
  private final boolean allowPublic;
  private final String baseUrl;

  public CoinGeckoClient(
      WebClient webClient,
      @Value("${COINGECKO_PRO_API_KEY:}") String apiKey,
      @Value("${COINGECKO_ALLOW_PUBLIC:false}") boolean allowPublic) {
    this.webClient = webClient;
    this.apiKey = apiKey == null ? "" : apiKey.trim();
    this.allowPublic = allowPublic;
    // Prefer Pro API when key is provided; optionally fall back to the public API (rate-limited).
    if (!this.apiKey.isBlank()) {
      this.baseUrl = "https://pro-api.coingecko.com/api/v3";
    } else if (this.allowPublic) {
      this.baseUrl = "https://api.coingecko.com/api/v3";
    } else {
      this.baseUrl = "";
    }
  }

  public boolean isEnabled() {
    return !baseUrl.isBlank();
  }

  public Optional<PriceMarketData> fetchSimpleUsdQuote(String coinId) {
    if (!isEnabled() || coinId == null || coinId.isBlank()) {
      if (log.isDebugEnabled()) {
        log.debug(
            "CoinGecko simple price skipped: apiKeyBlank={} coinId='{}'",
            apiKey.isBlank(),
            coinId);
      }
      return Optional.empty();
    }

    URI uri =
        UriComponentsBuilder.fromUriString(baseUrl + "/simple/price")
            .queryParam("ids", coinId)
            .queryParam("vs_currencies", "usd")
            .queryParam("include_24hr_change", "true")
            .build(true)
            .toUri();

    try {
      JsonNode root =
          webClient
              .get()
              .uri(uri)
              .headers(h -> {
                if (!apiKey.isBlank()) h.set("x-cg-pro-api-key", apiKey);
              })
              .retrieve()
              .bodyToMono(JsonNode.class)
              .timeout(Duration.ofSeconds(10))
              .block();
      if (root == null) {
        log.warn("CoinGecko simple price returned null body for id='{}'", coinId);
        return Optional.empty();
      }
      JsonNode priceNode = root.path(coinId).path("usd");
      Double price = parseMaybeDouble(priceNode);
      if (price == null || price <= 0d) {
        log.warn("CoinGecko simple price missing numeric 'usd' for id='{}', body={}", coinId, root);
        return Optional.empty();
      }
      Double change24hPct = parseMaybeDouble(root.path(coinId).path("usd_24h_change"));
      return Optional.of(new PriceMarketData(price, change24hPct));
    } catch (Exception e) {
      log.warn("CoinGecko simple price request failed for id='{}' uri={}", coinId, uri, e);
      return Optional.empty();
    }
  }

  /**
   * Contract price via CoinGecko platform endpoint.
   *
   * Returns: map(contractAddressCanonical -> quote)
   */
  public Map<String, PriceMarketData> fetchTokenQuotesByContract(
      int chainId, String platformId, String addressesCsv) {
    Map<String, PriceMarketData> out = new HashMap<>();
    if (!isEnabled() || platformId == null || platformId.isBlank()) {
      if (log.isDebugEnabled()) {
        log.debug(
            "CoinGecko token_price skipped: apiKeyBlank={} platformId='{}' addresses='{}'",
            apiKey.isBlank(),
            platformId,
            addressesCsv);
      }
      return out;
    }
    if (addressesCsv == null || addressesCsv.isBlank()) return out;

    URI uri =
        UriComponentsBuilder.fromUriString(
                baseUrl + "/simple/token_price/" + platformId)
            .queryParam("contract_addresses", addressesCsv)
            .queryParam("vs_currencies", "usd")
            .queryParam("include_24hr_change", "true")
            .build(true)
            .toUri();

    try {
      JsonNode root =
          webClient
              .get()
              .uri(uri)
              .headers(h -> {
                if (!apiKey.isBlank()) h.set("x-cg-pro-api-key", apiKey);
              })
              .retrieve()
              .bodyToMono(JsonNode.class)
              .timeout(Duration.ofSeconds(15))
              .block();
      if (root == null || !root.isObject()) {
        log.warn(
            "CoinGecko token_price returned invalid body for chainId={} platformId='{}' addresses='{}' body={}",
            chainId,
            platformId,
            addressesCsv,
            root);
        return out;
      }

      root.fieldNames()
          .forEachRemaining(
              (addr) -> {
                Double price = parseMaybeDouble(root.path(addr).path("usd"));
                if (price == null || price <= 0d) return;
                Double change24hPct =
                    parseMaybeDouble(root.path(addr).path("usd_24h_change"));
                out.put(
                    normalizeAddressKey(chainId, addr),
                    new PriceMarketData(price, change24hPct));
              });

      return out;
    } catch (Exception e) {
      log.warn(
          "CoinGecko token_price request failed for chainId={} platformId='{}' addresses='{}' uri={}",
          chainId,
          platformId,
          addressesCsv,
          uri,
          e);
      return out;
    }
  }

  public Map<String, PriceMarketData> fetchOnchainTokenQuotes(
      int chainId,
      String networkId,
      String addressesCsv,
      boolean includeInactiveSource) {
    Map<String, PriceMarketData> out = new HashMap<>();
    if (!isEnabled() || networkId == null || networkId.isBlank()) {
      if (log.isDebugEnabled()) {
        log.debug(
            "CoinGecko onchain token_price skipped: apiKeyBlank={} networkId='{}' addresses='{}'",
            apiKey.isBlank(),
            networkId,
            addressesCsv);
      }
      return out;
    }
    if (addressesCsv == null || addressesCsv.isBlank()) return out;

    URI uri =
        UriComponentsBuilder.fromUriString(
                baseUrl + "/onchain/simple/networks/" + networkId + "/token_price/" + addressesCsv)
            .queryParam("include_24hr_price_change", "true")
            .queryParam("include_inactive_source", includeInactiveSource ? "true" : "false")
            .build(true)
            .toUri();

    try {
      JsonNode root =
          webClient
              .get()
              .uri(uri)
              .headers(h -> {
                if (!apiKey.isBlank()) h.set("x-cg-pro-api-key", apiKey);
              })
              .retrieve()
              .bodyToMono(JsonNode.class)
              .timeout(Duration.ofSeconds(20))
              .block();
      if (root == null || !root.isObject()) {
        log.warn(
            "CoinGecko onchain token_price returned invalid body for chainId={} networkId='{}' addresses='{}' body={}",
            chainId,
            networkId,
            addressesCsv,
            root);
        return out;
      }

      JsonNode attrs = root.path("data").path("attributes");
      JsonNode tokenPrices = attrs.path("token_prices");
      JsonNode changes = attrs.path("h24_price_change_percentage");
      if (!tokenPrices.isObject()) return out;

      tokenPrices.fieldNames()
          .forEachRemaining(
              (addr) -> {
                Double price = parseMaybeDouble(tokenPrices.path(addr));
                if (price == null || price <= 0d) return;
                Double change24hPct = parseMaybeDouble(changes.path(addr));
                out.put(
                    normalizeAddressKey(chainId, addr),
                    new PriceMarketData(price, change24hPct));
              });
      return out;
    } catch (Exception e) {
      log.warn(
          "CoinGecko onchain token_price request failed for chainId={} networkId='{}' addresses='{}' uri={}",
          chainId,
          networkId,
          addressesCsv,
          uri,
          e);
      return out;
    }
  }

  /**
   * Top liquidity pool address for a token, used as the OHLCV source pool. Backed by GeckoTerminal
   * data (same provider as CoinGecko's onchain endpoints).
   */
  public Optional<String> fetchOnchainTopPoolAddress(String networkId, String tokenAddress) {
    if (!isEnabled() || networkId == null || networkId.isBlank() || tokenAddress == null || tokenAddress.isBlank()) {
      return Optional.empty();
    }

    URI uri =
        UriComponentsBuilder.fromUriString(
                baseUrl + "/onchain/networks/" + networkId + "/tokens/" + tokenAddress + "/pools")
            .queryParam("page", 1)
            .build(true)
            .toUri();

    try {
      JsonNode root =
          webClient
              .get()
              .uri(uri)
              .headers(h -> {
                if (!apiKey.isBlank()) h.set("x-cg-pro-api-key", apiKey);
              })
              .retrieve()
              .bodyToMono(JsonNode.class)
              .timeout(Duration.ofSeconds(15))
              .block();
      if (root == null) return Optional.empty();
      JsonNode first = root.path("data").get(0);
      if (first == null) return Optional.empty();
      String address = first.path("attributes").path("address").asText(null);
      if (address == null || address.isBlank()) return Optional.empty();
      return Optional.of(address);
    } catch (Exception e) {
      log.warn(
          "CoinGecko onchain top-pool lookup failed for networkId='{}' token='{}' uri={}",
          networkId,
          tokenAddress,
          uri,
          e);
      return Optional.empty();
    }
  }

  /**
   * OHLCV candles for a pool. {@code timeframe} is one of day/hour/minute; {@code aggregate} must be
   * a value GeckoTerminal supports for that timeframe (day:1, hour:1/4/12, minute:1/5/15).
   */
  public List<PriceCandle> fetchOnchainPoolOhlcv(
      String networkId, String poolAddress, String timeframe, int aggregate, int limit) {
    if (!isEnabled()
        || networkId == null
        || networkId.isBlank()
        || poolAddress == null
        || poolAddress.isBlank()) {
      return List.of();
    }

    URI uri =
        UriComponentsBuilder.fromUriString(
                baseUrl + "/onchain/networks/" + networkId + "/pools/" + poolAddress + "/ohlcv/" + timeframe)
            .queryParam("aggregate", aggregate)
            .queryParam("limit", limit)
            .queryParam("currency", "usd")
            .queryParam("token", "base")
            .build(true)
            .toUri();

    try {
      JsonNode root =
          webClient
              .get()
              .uri(uri)
              .headers(h -> {
                if (!apiKey.isBlank()) h.set("x-cg-pro-api-key", apiKey);
              })
              .retrieve()
              .bodyToMono(JsonNode.class)
              .timeout(Duration.ofSeconds(20))
              .block();
      if (root == null) return List.of();
      JsonNode list = root.path("data").path("attributes").path("ohlcv_list");
      if (!list.isArray()) return List.of();

      List<PriceCandle> out = new ArrayList<>();
      for (JsonNode row : list) {
        if (!row.isArray() || row.size() < 6) continue;
        out.add(
            new PriceCandle(
                row.get(0).asLong() * 1000L,
                row.get(1).asDouble(),
                row.get(2).asDouble(),
                row.get(3).asDouble(),
                row.get(4).asDouble(),
                row.get(5).asDouble(),
                null));
      }
      return out;
    } catch (Exception e) {
      log.warn(
          "CoinGecko onchain OHLCV request failed for networkId='{}' pool='{}' timeframe='{}' uri={}",
          networkId,
          poolAddress,
          timeframe,
          uri,
          e);
      return List.of();
    }
  }

  /** Market-wide OHLC candles for a listed coin. No volume data (CoinGecko OHLC limitation). */
  public List<PriceCandle> fetchCoinOhlc(String coinId, int days) {
    if (!isEnabled() || coinId == null || coinId.isBlank()) return List.of();

    URI uri =
        UriComponentsBuilder.fromUriString(baseUrl + "/coins/" + coinId + "/ohlc")
            .queryParam("vs_currency", "usd")
            .queryParam("days", days)
            .build(true)
            .toUri();

    try {
      JsonNode root =
          webClient
              .get()
              .uri(uri)
              .headers(h -> {
                if (!apiKey.isBlank()) h.set("x-cg-pro-api-key", apiKey);
              })
              .retrieve()
              .bodyToMono(JsonNode.class)
              .timeout(Duration.ofSeconds(15))
              .block();
      if (root == null || !root.isArray()) return List.of();

      List<PriceCandle> out = new ArrayList<>();
      for (JsonNode row : root) {
        if (!row.isArray() || row.size() < 5) continue;
        out.add(
            new PriceCandle(
                row.get(0).asLong(),
                row.get(1).asDouble(),
                row.get(2).asDouble(),
                row.get(3).asDouble(),
                row.get(4).asDouble(),
                null,
                null));
      }
      return out;
    } catch (Exception e) {
      log.warn("CoinGecko OHLC request failed for coinId='{}' days={} uri={}", coinId, days, e);
      return List.of();
    }
  }

  private static String normalizeAddressKey(int chainId, String address) {
    String trimmed = address == null ? "" : address.trim();
    if (trimmed.isBlank()) return "";
    if (chainId == 195 || chainId == 501) {
      return trimmed;
    }
    return trimmed.toLowerCase();
  }

  private static Double parseMaybeDouble(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) return null;
    if (node.isNumber()) return node.asDouble();
    if (node.isTextual()) {
      String text = node.asText("").trim();
      if (text.isBlank()) return null;
      try {
        return Double.parseDouble(text);
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }
}
