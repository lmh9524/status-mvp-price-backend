package io.statusmvp.pricebackend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class PriceHistoryServiceTest {
  private final Map<String, String> store = new HashMap<>();
  private PriceHistoryService service;

  @BeforeEach
  void setUp() {
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    StringRedisTemplate redis = mock(StringRedisTemplate.class);

    when(redis.opsForValue()).thenReturn(valueOps);
    when(valueOps.get(anyString())).thenAnswer(invocation -> store.get(invocation.getArgument(0)));
    doAnswer(
            invocation -> {
              String key = invocation.getArgument(0);
              String value = invocation.getArgument(1);
              store.put(key, value);
              return null;
            })
        .when(valueOps)
        .set(anyString(), anyString(), any(Duration.class));

    service = new PriceHistoryService(redis, 60, 0, 259200);
    store.clear();
  }

  @Test
  void returnsComputed24hChangeWhenHistoricalBaselineExists() {
    String assetKey = PriceHistoryService.symbolAssetKey("VEILX");
    long firstTs = Duration.ofHours(1).toMillis();
    long secondTs = Duration.ofHours(25).toMillis();

    Double initial = service.resolveChange24hPct(assetKey, 100d, null, firstTs);
    Double computed = service.resolveChange24hPct(assetKey, 110d, null, secondTs);

    assertNull(initial);
    assertEquals(10d, computed, 0.000001d);
  }

  @Test
  void keepsUpstream24hChangeWhenProviderAlreadyReturnedIt() {
    String assetKey = PriceHistoryService.contractAssetKey(56, "0x1234");
    Double resolved = service.resolveChange24hPct(assetKey, 1.23d, -0.42d, Duration.ofHours(8).toMillis());
    assertEquals(-0.42d, resolved, 0.000001d);
  }

  @Test
  void buildsStableAssetKeys() {
    assertEquals("symbol:USDT", PriceHistoryService.symbolAssetKey("usdt"));
    assertEquals("contract:501:EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", PriceHistoryService.contractAssetKey(501, "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"));
  }
}
