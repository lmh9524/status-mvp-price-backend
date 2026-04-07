package io.statusmvp.pricebackend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.statusmvp.pricebackend.model.PortfolioAssetSnapshotV2;
import io.statusmvp.pricebackend.model.PriceQuote;
import io.statusmvp.pricebackend.model.PortfolioSnapshot;
import io.statusmvp.pricebackend.model.PortfolioSnapshotV2;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.reactive.function.client.WebClient;
import org.web3j.protocol.Web3j;

class PortfolioAggregatorServiceTest {
  private static final String VALID_ADDRESS = "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045";

  private RedisCache cache;
  private PortfolioAggregatorService service;
  private PriceAggregatorService priceAggregator;

  @BeforeEach
  void setUp() {
    cache = mock(RedisCache.class);
    when(cache.get(org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());
    @SuppressWarnings("unchecked")
    ObjectProvider<Web3j> bscWeb3jProvider = mock(ObjectProvider.class);
    when(bscWeb3jProvider.getIfAvailable()).thenReturn(null);
    VeilxDexPriceService veilxDex = mock(VeilxDexPriceService.class);
    priceAggregator = mock(PriceAggregatorService.class);
    when(priceAggregator.getPricesByContract(org.mockito.ArgumentMatchers.anyInt(), anyList(), org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(List.of());
    when(priceAggregator.getPrices(anyList(), org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(List.of());

    service =
        new PortfolioAggregatorService(
            WebClient.builder().build(),
            cache,
            bscWeb3jProvider,
            veilxDex,
            priceAggregator,
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
  void getPortfolioReturnsZeroWhenDataUnavailable() {
    PortfolioSnapshot snapshot = service.getPortfolio(VALID_ADDRESS, List.of(56, 56, 999, 8453));
    assertEquals(VALID_ADDRESS, snapshot.address());
    assertEquals(2, snapshot.chains().size());
    assertEquals(0.0, snapshot.totalUsd());
    assertTrue(snapshot.fetchedAt() > 0);
  }

  @Test
  void explicitUnsupportedChainIdsDoNotFallbackToDefaultForV2() {
    PortfolioSnapshotV2 snapshot =
        service.getPortfolioSnapshotV2(VALID_ADDRESS, List.of(97, 11155111), "usd", 0.01d, false, 200, true);
    assertEquals(VALID_ADDRESS, snapshot.address());
    assertEquals(0, snapshot.assets().size());
    assertEquals(0.0, snapshot.totalUsd());
  }

  @Test
  void backfillMissingUsdDataUsesSymbolFallbackForNativeAndStableAssets() {
    when(priceAggregator.getPrices(anyList(), eq("usd")))
        .thenReturn(
            List.of(
                new PriceQuote("BNB", 600d, 4.5d, "usd", 1L, "mock", null, null),
                new PriceQuote("USDT", 1d, 0.01d, "usd", 1L, "stablecoin_fallback", null, null)));

    List<PortfolioAssetSnapshotV2> enriched =
        service.backfillMissingUsdData(
            List.of(
                new PortfolioAssetSnapshotV2(
                    56,
                    "bsc",
                    true,
                    null,
                    "BNB",
                    "BNB",
                    18,
                    "1000000000000000000",
                    "1",
                    null,
                    null,
                    null,
                    null,
                    null),
                new PortfolioAssetSnapshotV2(
                    56,
                    "bsc",
                    false,
                    "0x55d398326f99059ff775485246999027b3197955",
                    "USD₮",
                    "Tether USD",
                    18,
                    "1140340000000000000",
                    "1.14034",
                    null,
                    null,
                    null,
                    null,
                    null)),
            "usd");

    assertEquals(600d, enriched.get(0).usdPrice(), 0.000001d);
    assertEquals(600d, enriched.get(0).usdValue(), 0.000001d);
    assertEquals(4.5d, enriched.get(0).change24hPct(), 0.000001d);
    assertEquals(1d, enriched.get(1).usdPrice(), 0.000001d);
    assertEquals(1.14034d, enriched.get(1).usdValue(), 0.000001d);
    assertEquals(0.01d, enriched.get(1).change24hPct(), 0.000001d);

    verify(priceAggregator).getPricesByContract(eq(56), anyList(), eq("usd"));
    verify(priceAggregator).getPrices(anyList(), eq("usd"));
  }
}
