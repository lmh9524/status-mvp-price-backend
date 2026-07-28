package io.statusmvp.pricebackend.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.statusmvp.pricebackend.model.PriceMarketData;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class DexScreenerClientTest {
  private static final String VEILX = "0x8435de540ed40903b6e74181d13cead693e27888";
  private static final String VEIL = "0xddcec2492a48047b494e21ac97ed7e066307d999";

  @Test
  void fetchTokenQuotesByContractParsesPricesForRequestedBscTokens() {
    WebClient webClient =
        WebClient.builder()
            .exchangeFunction(
                request -> {
                  assertEquals(
                      "/tokens/v1/bsc/" + VEILX + "," + VEIL,
                      request.url().getRawPath());
                  return Mono.just(
                      ClientResponse.create(HttpStatus.OK)
                          .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                          .body(
                              """
                              [
                                {
                                  "chainId": "bsc",
                                  "baseToken": {
                                    "address": "0x8435dE540ED40903B6E74181D13cEAD693E27888",
                                    "symbol": "VEILX"
                                  },
                                  "quoteToken": {
                                    "address": "0x55d398326f99059fF775485246999027B3197955",
                                    "symbol": "USDT"
                                  },
                                  "priceUsd": "0.0123",
                                  "priceChange": { "h24": 4.56 },
                                  "liquidity": { "usd": 1000 }
                                },
                                {
                                  "chainId": "bsc",
                                  "baseToken": {
                                    "address": "0xdDcEC2492a48047b494E21Ac97ed7E066307d999",
                                    "symbol": "VEIL"
                                  },
                                  "quoteToken": {
                                    "address": "0x55d398326f99059fF775485246999027B3197955",
                                    "symbol": "USDT"
                                  },
                                  "priceUsd": "0.0042",
                                  "priceChange": { "h24": -1.25 },
                                  "liquidity": { "usd": 500 }
                                }
                              ]
                              """)
                          .build());
                })
            .build();
    DexScreenerClient client = new DexScreenerClient(webClient, true, "https://api.dexscreener.com");

    Map<String, PriceMarketData> quotes =
        client.fetchTokenQuotesByContract(56, List.of(VEILX, VEIL));

    assertEquals(0.0123d, quotes.get(VEILX).price(), 0.0000001d);
    assertEquals(4.56d, quotes.get(VEILX).change24hPct(), 0.0000001d);
    assertEquals(0.0042d, quotes.get(VEIL).price(), 0.0000001d);
    assertEquals(-1.25d, quotes.get(VEIL).change24hPct(), 0.0000001d);
  }

  @Test
  void fetchTokenQuotesByContractSkipsUnsupportedChains() {
    DexScreenerClient client =
        new DexScreenerClient(WebClient.builder().build(), true, "https://api.dexscreener.com");

    assertTrue(client.fetchTokenQuotesByContract(999, List.of(VEILX)).isEmpty());
  }
}
