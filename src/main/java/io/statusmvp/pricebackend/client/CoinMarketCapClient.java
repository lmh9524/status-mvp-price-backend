package io.statusmvp.pricebackend.client;

import com.fasterxml.jackson.databind.JsonNode;
import io.statusmvp.pricebackend.model.PriceMarketData;
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

  public Optional<PriceMarketData> fetchUsdQuoteBySymbol(String symbol) {
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

      JsonNode symbolNode = root.path("data").path(symbol.toUpperCase());
      if (symbolNode.isArray()) {
        symbolNode = symbolNode.size() > 0 ? symbolNode.get(0) : null;
      }
      if (symbolNode == null) return Optional.empty();

      JsonNode usdQuote = symbolNode.path("quote").path("USD");
      Double price = parseMaybeDouble(usdQuote.path("price"));
      if (price == null || price <= 0d) return Optional.empty();
      Double change24hPct = parseMaybeDouble(usdQuote.path("percent_change_24h"));
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

