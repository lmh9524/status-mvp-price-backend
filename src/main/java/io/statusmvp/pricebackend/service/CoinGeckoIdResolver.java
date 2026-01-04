package io.statusmvp.pricebackend.service;

import io.statusmvp.pricebackend.util.PriceMappings;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves CoinGecko "coin id" from a requested token symbol.
 *
 * <p>Defaults to {@link PriceMappings#COINGECKO_IDS} but can be extended/overridden using the env var
 * {@code COINGECKO_SYMBOL_ID_OVERRIDES}.
 *
 * <p>Format: {@code SYMBOL=id,SYMBOL2=id2} (comma-separated pairs). Symbols are uppercased and ids are
 * trimmed. Invalid pairs are ignored.
 */
@Component
public class CoinGeckoIdResolver {
  private final Map<String, String> merged;

  public CoinGeckoIdResolver(@Value("${app.coingecko.symbolIdOverrides:}") String overrides) {
    Map<String, String> map = new HashMap<>(PriceMappings.COINGECKO_IDS);
    parseOverrides(overrides).forEach(map::put);
    this.merged = Collections.unmodifiableMap(map);
  }

  public String resolve(String symbol) {
    if (symbol == null) return null;
    String s = symbol.trim().toUpperCase(Locale.ROOT);
    if (s.isBlank()) return null;
    return merged.get(s);
  }

  static Map<String, String> parseOverrides(String raw) {
    if (raw == null || raw.isBlank()) return Map.of();
    Map<String, String> out = new HashMap<>();
    for (String part : raw.split(",")) {
      if (part == null) continue;
      String p = part.trim();
      if (p.isEmpty()) continue;
      int eq = p.indexOf('=');
      if (eq <= 0 || eq >= p.length() - 1) continue;
      String symbol = p.substring(0, eq).trim().toUpperCase(Locale.ROOT);
      String id = p.substring(eq + 1).trim();
      if (symbol.isEmpty() || id.isEmpty()) continue;
      out.put(symbol, id);
    }
    return out;
  }
}



