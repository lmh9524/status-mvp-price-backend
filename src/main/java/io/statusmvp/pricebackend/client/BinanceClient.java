package io.statusmvp.pricebackend.client;

import com.fasterxml.jackson.databind.JsonNode;
import io.statusmvp.pricebackend.model.PriceMarketData;
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
  public Optional<PriceMarketData> fetchUsdQuoteViaUsdtPair(String baseSymbol) {
    if (baseSymbol == null || baseSymbol.isBlank()) return Optional.empty();
    String symbol = baseSymbol.toUpperCase() + "USDT";

    try {
      URI uri =
          UriComponentsBuilder.fromUriString("https://api.binance.com/api/v3/ticker/24hr")
              .queryParam("symbol", symbol)
              .build(true)
              .toUri();
      JsonNode root =
          webClient
              .get()
              .uri(uri)
              .retrieve()
              .bodyToMono(JsonNode.class)
              .timeout(Duration.ofSeconds(8))
              .block();
      if (root == null) return Optional.empty();
      Double price = parseMaybeDouble(root.path("lastPrice"));
      if (price == null || price <= 0d) return Optional.empty();
      Double change24hPct = parseMaybeDouble(root.path("priceChangePercent"));
      return Optional.of(new PriceMarketData(price, change24hPct));
    } catch (Exception ignored) {
      return Optional.empty();
    }
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

