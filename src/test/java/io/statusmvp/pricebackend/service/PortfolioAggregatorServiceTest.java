package io.statusmvp.pricebackend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.statusmvp.pricebackend.model.PortfolioSnapshot;
import io.statusmvp.pricebackend.model.PriceQuote;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class PortfolioAggregatorServiceTest {
  private static final String VALID_ADDRESS = "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045";

  private PriceAggregatorService prices;
  private RedisCache cache;
  private PortfolioAggregatorService service;

  @BeforeEach
  void setUp() {
    prices = mock(PriceAggregatorService.class);
    cache = mock(RedisCache.class);
    when(cache.get(org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());
    when(prices.getPrices(anyList(), eq("usd")))
        .thenReturn(List.of(new PriceQuote("ETH", 3000.0, "usd", 1739011200000L, "mock", null, null)));
    when(prices.getPricesByContract(org.mockito.ArgumentMatchers.anyInt(), anyList(), eq("usd")))
        .thenReturn(List.of());

    service =
        new PortfolioAggregatorService(
            WebClient.builder().build(),
            prices,
            cache,
            "https://rpc.ankr.com/multichain",
            "",
            30,
            3000,
            "1,10,56,8453,42161");
  }

  @Test
  void parseChainIdsKeepsValidNumbers() {
    List<Integer> parsed = service.parseChainIds("56, 8453,foo, ,42161");
    assertEquals(List.of(56, 8453, 42161), parsed);
  }

  @Test
  void getPortfolioRejectsInvalidAddress() {
    assertThrows(
        IllegalArgumentException.class,
        () -> service.getPortfolio("0x123", List.of(56)));
  }

  @Test
  void getPortfolioReturnsSupportedUniqueChainsWhenDataUnavailable() {
    PortfolioSnapshot snapshot = service.getPortfolio(VALID_ADDRESS, List.of(56, 56, 999, 8453));
    assertEquals(VALID_ADDRESS, snapshot.address());
    assertEquals(2, snapshot.chains().size());
    assertEquals(56, snapshot.chains().get(0).chainId());
    assertEquals(8453, snapshot.chains().get(1).chainId());
    assertEquals(0.0, snapshot.totalUsd());
    assertTrue(snapshot.chains().stream().allMatch(c -> "0".equals(c.nativeBalance())));
  }
}

