package io.statusmvp.pricebackend.client;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class CoinGeckoClient {
  private final WebClient webClient;
  private final String apiKey;

  public CoinGeckoClient(WebClient webClient, @Value("${COINGECKO_PRO_API_KEY:}") String apiKey) {
    this.webClient = webClient;
    this.apiKey = apiKey == null ? "" : apiKey.trim();
  }

  public boolean isEnabled() {
    return !apiKey.isBlank();
  }

  public Optional<Double> fetchSimplePriceUsd(String coinId) {
    if (apiKey.isBlank() || coinId == null || coinId.isBlank()) return Optional.empty();

    URI uri =
        UriComponentsBuilder.fromUriString("https://pro-api.coingecko.com/api/v3/simple/price")
            .queryParam("ids", coinId)
            .queryParam("vs_currencies", "usd")
            .build(true)
            .toUri();

    try {
      JsonNode root =
          webClient
              .get()
              .uri(uri)
              .header("x-cg-pro-api-key", apiKey)
              .retrieve()
              .bodyToMono(JsonNode.class)
              .timeout(Duration.ofSeconds(10))
              .block();
      if (root == null) return Optional.empty();
      JsonNode node = root.path(coinId).path("usd");
      if (!node.isNumber()) return Optional.empty();
      double price = node.asDouble();
      return price > 0 ? Optional.of(price) : Optional.empty();
    } catch (Exception ignored) {
      return Optional.empty();
    }
  }

  /**
   * Contract price via CoinGecko platform endpoint.
   *
   * Returns: map(contractAddressLower -> usdPrice)
   */
  public Map<String, Double> fetchTokenPricesByContract(int chainId, String platformId, String addressesCsv) {
    Map<String, Double> out = new HashMap<>();
    if (apiKey.isBlank() || platformId == null || platformId.isBlank()) return out;
    if (addressesCsv == null || addressesCsv.isBlank()) return out;

    URI uri =
        UriComponentsBuilder.fromUriString(
                "https://pro-api.coingecko.com/api/v3/simple/token_price/" + platformId)
            .queryParam("contract_addresses", addressesCsv)
            .queryParam("vs_currencies", "usd")
            .build(true)
            .toUri();

    try {
      JsonNode root =
          webClient
              .get()
              .uri(uri)
              .header("x-cg-pro-api-key", apiKey)
              .retrieve()
              .bodyToMono(JsonNode.class)
              .timeout(Duration.ofSeconds(15))
              .block();
      if (root == null || !root.isObject()) return out;

      root.fieldNames()
          .forEachRemaining(
              (addr) -> {
                JsonNode usd = root.path(addr).path("usd");
                if (usd != null && usd.isNumber()) {
                  double p = usd.asDouble();
                  if (p > 0) out.put(addr.toLowerCase(), p);
                }
              });

      return out;
    } catch (Exception ignored) {
      return out;
    }
  }
}


