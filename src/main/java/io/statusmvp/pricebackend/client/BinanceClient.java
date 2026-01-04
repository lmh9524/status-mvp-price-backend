package io.statusmvp.pricebackend.client;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class BinanceClient {
  private final WebClient webClient;

  public BinanceClient(WebClient webClient) {
    this.webClient = webClient;
  }

  /**
   * Binance doesn't provide USD directly; use USDT pair and assume USDT≈USD for MVP.
   */
  public Optional<Double> fetchUsdPriceViaUsdtPair(String baseSymbol) {
    if (baseSymbol == null || baseSymbol.isBlank()) return Optional.empty();
    String symbol = baseSymbol.toUpperCase() + "USDT";

    URI uri =
        UriComponentsBuilder.fromUriString("https://api.binance.com/api/v3/ticker/price")
            .queryParam("symbol", symbol)
            .build(true)
            .toUri();

    try {
      JsonNode root =
          webClient
              .get()
              .uri(uri)
              .retrieve()
              .bodyToMono(JsonNode.class)
              .timeout(Duration.ofSeconds(8))
              .block();
      if (root == null) return Optional.empty();
      JsonNode priceNode = root.path("price");
      if (!priceNode.isTextual()) return Optional.empty();
      double price = Double.parseDouble(priceNode.asText());
      return price > 0 ? Optional.of(price) : Optional.empty();
    } catch (Exception ignored) {
      return Optional.empty();
    }
  }
}


