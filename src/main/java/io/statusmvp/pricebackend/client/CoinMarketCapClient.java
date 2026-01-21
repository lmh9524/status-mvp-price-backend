package io.statusmvp.pricebackend.client;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class CoinMarketCapClient {
  private final WebClient webClient;
  private final String apiKey;

  public CoinMarketCapClient(WebClient webClient, @Value("${COINMARKETCAP_API_KEY:}") String apiKey) {
    this.webClient = webClient;
    this.apiKey = apiKey == null ? "" : apiKey.trim();
  }

  public boolean isEnabled() {
    return !apiKey.isBlank();
  }

  public Optional<Double> fetchUsdPriceBySymbol(String symbol) {
    if (apiKey.isBlank() || symbol == null || symbol.isBlank()) return Optional.empty();

    try {
      URI uri =
          UriComponentsBuilder.fromUriString(
                  "https://pro-api.coinmarketcap.com/v1/cryptocurrency/quotes/latest")
              .queryParam("symbol", symbol.toUpperCase())
              .queryParam("convert", "USD")
              .build(true)
              .toUri();
      JsonNode root =
          webClient
              .get()
              .uri(uri)
              .header("X-CMC_PRO_API_KEY", apiKey)
              .retrieve()
              .bodyToMono(JsonNode.class)
              .timeout(Duration.ofSeconds(10))
              .block();
      if (root == null) return Optional.empty();

      JsonNode priceNode =
          root.path("data").path(symbol.toUpperCase()).path("quote").path("USD").path("price");
      if (!priceNode.isNumber()) return Optional.empty();
      double price = priceNode.asDouble();
      return price > 0 ? Optional.of(price) : Optional.empty();
    } catch (Exception ignored) {
      return Optional.empty();
    }
  }
}


