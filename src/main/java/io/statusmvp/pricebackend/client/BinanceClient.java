package io.statusmvp.pricebackend.client;

import com.fasterxml.jackson.databind.JsonNode;
import io.statusmvp.pricebackend.model.PriceCandle;
import io.statusmvp.pricebackend.model.PriceMarketData;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class BinanceClient {
  private static final Logger log = LoggerFactory.getLogger(BinanceClient.class);
  private final WebClient webClient;

  public BinanceClient(WebClient webClient) {
    this.webClient = webClient;
  }

  /**
   * Klines/candles via the USDT pair (assumes USDT~USD for MVP, matching {@link
   * #fetchUsdQuoteViaUsdtPair}). {@code interval} must already be a Binance-supported value
   * (1m/5m/15m/1h/4h/1d, ...).
   */
  public List<PriceCandle> fetchKlinesViaUsdtPair(String baseSymbol, String interval, int limit) {
    if (baseSymbol == null || baseSymbol.isBlank()) return List.of();
    String symbol = baseSymbol.toUpperCase() + "USDT";

    URI uri =
        UriComponentsBuilder.fromUriString("https://api.binance.com/api/v3/klines")
            .queryParam("symbol", symbol)
            .queryParam("interval", interval)
            .queryParam("limit", limit)
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
      if (root == null || !root.isArray()) return List.of();

      List<PriceCandle> out = new ArrayList<>();
      for (JsonNode row : root) {
        if (!row.isArray() || row.size() < 6) continue;
        out.add(
            new PriceCandle(
                row.get(0).asLong(),
                row.get(1).asDouble(),
                row.get(2).asDouble(),
                row.get(3).asDouble(),
                row.get(4).asDouble(),
                row.get(5).asDouble(),
                row.size() > 8 ? row.get(8).asLong() : null));
      }
      return out;
    } catch (Exception e) {
      log.warn("Binance klines request failed for symbol='{}' interval='{}'", symbol, interval, e);
      return List.of();
    }
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

