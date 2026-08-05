package io.statusmvp.pricebackend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.statusmvp.pricebackend.client.BinanceClient;
import io.statusmvp.pricebackend.client.CoinGeckoClient;
import io.statusmvp.pricebackend.client.CoinMarketCapClient;
import io.statusmvp.pricebackend.client.DexScreenerClient;
import io.statusmvp.pricebackend.model.PriceQuote;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class PriceAggregatorServiceTest {
  private final Map<String, String> store = new HashMap<>();
  private PriceAggregatorService service;

  @BeforeEach
  void setUp() {
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    when(redis.opsForValue()).thenReturn(valueOps);
    when(valueOps.get(anyString())).thenAnswer(invocation -> store.get(invocation.getArgument(0)));
    doAnswer(
            invocation -> {
              store.put(invocation.getArgument(0), invocation.getArgument(1));
              return null;
            })
        .when(valueOps)
        .set(anyString(), anyString(), any(Duration.class));
    RedisCache cache = new RedisCache(redis);

    CoinGeckoClient coinGecko = mock(CoinGeckoClient.class);
    when(coinGecko.isEnabled()).thenReturn(false);
    CoinMarketCapClient cmc = mock(CoinMarketCapClient.class);
    when(cmc.isEnabled()).thenReturn(false);
    BinanceClient binance = mock(BinanceClient.class);
    DexScreenerClient dexScreener = mock(DexScreenerClient.class);
    when(dexScreener.isEnabled()).thenReturn(false);
    CoinGeckoIdResolver coinGeckoIds = mock(CoinGeckoIdResolver.class);
    VeilxDexPriceService veilxDex = mock(VeilxDexPriceService.class);
    when(veilxDex.isEnabled()).thenReturn(false);
    PriceHistoryService priceHistory = mock(PriceHistoryService.class);

    service =
        new PriceAggregatorService(
            coinGecko,
            cmc,
            binance,
            dexScreener,
            cache,
            coinGeckoIds,
            veilxDex,
            priceHistory,
            /* priceTtlSeconds= */ 120,
            /* requestTtlSeconds= */ 30,
            /* lastGoodPriceTtlSeconds= */ 259200);
  }

  @Test
  void returnsNullPriceWhenNoLiveSourceAndNoPriorGoodQuote() {
    List<PriceQuote> quotes = service.getPrices(List.of("VEILX"), "usd");
    assertEquals(1, quotes.size());
    assertNull(quotes.get(0).price());
  }

  @Test
  void fallsBackToLastKnownGoodPriceWhenAllLiveSourcesMiss() {
    store.put("price:lastgood:VEILX:usd", "0.011873827");

    List<PriceQuote> quotes = service.getPrices(List.of("VEILX"), "usd");

    assertEquals(1, quotes.size());
    assertEquals(0.011873827, quotes.get(0).price());
    assertEquals("stale_cache", quotes.get(0).source());
  }

  @Test
  void refreshesLastKnownGoodPriceWhenALiveSourceSucceeds() {
    VeilxDexPriceService veilxDex = mock(VeilxDexPriceService.class);
    when(veilxDex.isEnabled()).thenReturn(true);
    when(veilxDex.fetchVeilxUsdPrice()).thenReturn(java.util.Optional.of(0.02d));

    @SuppressWarnings("unchecked")
    ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    when(redis.opsForValue()).thenReturn(valueOps);
    when(valueOps.get(anyString())).thenAnswer(invocation -> store.get(invocation.getArgument(0)));
    doAnswer(
            invocation -> {
              store.put(invocation.getArgument(0), invocation.getArgument(1));
              return null;
            })
        .when(valueOps)
        .set(anyString(), anyString(), any(Duration.class));
    RedisCache cache = new RedisCache(redis);

    CoinGeckoClient coinGecko = mock(CoinGeckoClient.class);
    when(coinGecko.isEnabled()).thenReturn(false);
    CoinMarketCapClient cmc = mock(CoinMarketCapClient.class);
    when(cmc.isEnabled()).thenReturn(false);
    BinanceClient binance = mock(BinanceClient.class);
    DexScreenerClient dexScreener = mock(DexScreenerClient.class);
    when(dexScreener.isEnabled()).thenReturn(false);
    CoinGeckoIdResolver coinGeckoIds = mock(CoinGeckoIdResolver.class);
    PriceHistoryService priceHistory = mock(PriceHistoryService.class);
    when(priceHistory.resolveChange24hPct(anyString(), any(), any(), org.mockito.ArgumentMatchers.anyLong()))
        .thenReturn(null);

    PriceAggregatorService liveService =
        new PriceAggregatorService(
            coinGecko,
            cmc,
            binance,
            dexScreener,
            cache,
            coinGeckoIds,
            veilxDex,
            priceHistory,
            120,
            30,
            259200);

    List<PriceQuote> quotes = liveService.getPrices(List.of("VEILX"), "usd");

    assertEquals(0.02, quotes.get(0).price());
    assertEquals("0.02", store.get("price:lastgood:VEILX:usd"));
  }
}
