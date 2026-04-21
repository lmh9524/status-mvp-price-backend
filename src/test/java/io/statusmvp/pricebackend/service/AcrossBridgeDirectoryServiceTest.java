package io.statusmvp.pricebackend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.statusmvp.pricebackend.model.bridge.BridgeAcrossDirectoryResponse;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class AcrossBridgeDirectoryServiceTest {
  private static final String CHAINS_JSON =
      """
      [
        {
          "chainId": 1,
          "name": "Ethereum",
          "publicRpcUrl": "https://eth.rpc",
          "explorerUrl": "https://etherscan.io",
          "logoUrl": null,
          "spokePool": "0xspoke",
          "spokePoolBlock": 1,
          "inputTokens": [
            { "address": "0x1111111111111111111111111111111111111111", "symbol": "USDC", "name": "USD Coin", "decimals": 6, "logoUrl": null },
            { "address": "0x2222222222222222222222222222222222222222", "symbol": "WBTC", "name": "Wrapped BTC", "decimals": 8, "logoUrl": null }
          ],
          "outputTokens": [
            { "address": "0x1111111111111111111111111111111111111111", "symbol": "USDC", "name": "USD Coin", "decimals": 6, "logoUrl": null },
            { "address": "0x2222222222222222222222222222222222222222", "symbol": "WBTC", "name": "Wrapped BTC", "decimals": 8, "logoUrl": null }
          ]
        },
        {
          "chainId": 10,
          "name": "Optimism",
          "publicRpcUrl": "https://op.rpc",
          "explorerUrl": "https://optimistic.etherscan.io",
          "logoUrl": null,
          "spokePool": "0xspoke",
          "spokePoolBlock": 1,
          "inputTokens": [
            { "address": "0x3333333333333333333333333333333333333333", "symbol": "DAI", "name": "Dai", "decimals": 18, "logoUrl": null }
          ],
          "outputTokens": [
            { "address": "0x3333333333333333333333333333333333333333", "symbol": "DAI", "name": "Dai", "decimals": 18, "logoUrl": null },
            { "address": "0x1111111111111111111111111111111111111111", "symbol": "USDC", "name": "USD Coin", "decimals": 6, "logoUrl": null }
          ]
        },
        {
          "chainId": 137,
          "name": "Polygon",
          "publicRpcUrl": "https://polygon.rpc",
          "explorerUrl": "https://polygonscan.com",
          "logoUrl": null,
          "spokePool": "0xspoke",
          "spokePoolBlock": 1,
          "inputTokens": [
            { "address": "0x4444444444444444444444444444444444444444", "symbol": "MATIC", "name": "Matic", "decimals": 18, "logoUrl": null }
          ],
          "outputTokens": [
            { "address": "0x4444444444444444444444444444444444444444", "symbol": "MATIC", "name": "Matic", "decimals": 18, "logoUrl": null }
          ]
        }
      ]
      """;

  private static final String ROUTES_JSON =
      """
      [
        {
          "isNative": false,
          "originChainId": 1,
          "destinationChainId": 10,
          "originToken": "0x1111111111111111111111111111111111111111",
          "destinationToken": "0x1111111111111111111111111111111111111111",
          "originTokenSymbol": "USDC",
          "destinationTokenSymbol": "USDC"
        },
        {
          "isNative": false,
          "originChainId": 1,
          "destinationChainId": 10,
          "originToken": "0x2222222222222222222222222222222222222222",
          "destinationToken": "0x3333333333333333333333333333333333333333",
          "originTokenSymbol": "WBTC",
          "destinationTokenSymbol": "DAI"
        },
        {
          "isNative": false,
          "originChainId": 1,
          "destinationChainId": 137,
          "originToken": "0x4444444444444444444444444444444444444444",
          "destinationToken": "0x4444444444444444444444444444444444444444",
          "originTokenSymbol": "MATIC",
          "destinationTokenSymbol": "MATIC"
        }
      ]
      """;

  @Test
  void strictTokenAllowlistKeepsLegacyFiltering() {
    AcrossBridgeDirectoryService service =
        createService("STRICT", "STRICT", "1,10", "USDC");

    BridgeAcrossDirectoryResponse response = service.getDirectory();

    assertIterableEquals(List.of(1L, 10L), response.allowlist().chainIds());
    assertIterableEquals(List.of("USDC"), response.allowlist().tokenSymbols());
    assertEquals(2, response.chains().size());
    assertEquals(List.of("USDC"), response.chains().get(0).inputTokens().stream().map(BridgeAcrossDirectoryResponse.Token::symbol).toList());
    assertEquals(1, response.routes().size());
    assertEquals("USDC", response.routes().get(0).inputTokenSymbol());
  }

  @Test
  void allOnAllowedChainsExpandsTokensWithoutExpandingChains() {
    AcrossBridgeDirectoryService service =
        createService("STRICT", "ALL_ON_ALLOWED_CHAINS", "1,10", "USDC");

    BridgeAcrossDirectoryResponse response = service.getDirectory();

    assertIterableEquals(List.of(1L, 10L), response.allowlist().chainIds());
    assertIterableEquals(List.of("DAI", "USDC", "WBTC"), response.allowlist().tokenSymbols());
    assertEquals(List.of(1L, 10L), response.chains().stream().map(BridgeAcrossDirectoryResponse.Chain::chainId).toList());
    assertEquals(2, response.routes().size());
    assertEquals(List.of("USDC", "WBTC"), response.chains().get(0).inputTokens().stream().map(BridgeAcrossDirectoryResponse.Token::symbol).toList());
  }

  private static AcrossBridgeDirectoryService createService(
      String allowlistMode,
      String tokenAllowlistMode,
      String allowedChainIds,
      String allowedTokenSymbols) {
    RedisCache cache = mock(RedisCache.class);
    when(cache.get("bridge:across:chains")).thenReturn(Optional.empty());
    when(cache.get("bridge:across:available-routes")).thenReturn(Optional.empty());
    doNothing().when(cache).set(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong());

    ExchangeFunction exchangeFunction =
        request ->
            Mono.just(
                ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body(responseBodyFor(request))
                    .build());

    WebClient webClient = WebClient.builder().exchangeFunction(exchangeFunction).build();
    return new AcrossBridgeDirectoryService(
        webClient,
        cache,
        "https://app.across.to/api",
        12000L,
        300L,
        60L,
        allowlistMode,
        tokenAllowlistMode,
        allowedChainIds,
        allowedTokenSymbols);
  }

  private static String responseBodyFor(ClientRequest request) {
    String path = request.url().getPath();
    if (path.endsWith("/chains")) return CHAINS_JSON;
    if (path.endsWith("/available-routes")) return ROUTES_JSON;
    throw new IllegalArgumentException("Unexpected request path: " + path);
  }
}
