package io.statusmvp.pricebackend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.statusmvp.pricebackend.model.PortfolioSnapshot;
import io.statusmvp.pricebackend.model.PortfolioSnapshotV2;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class PortfolioAggregatorServiceTest {
  private static final String VALID_ADDRESS = "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045";

  private RedisCache cache;
  private PortfolioAggregatorService service;

  @BeforeEach
  void setUp() {
    cache = mock(RedisCache.class);
    when(cache.get(org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());

    service =
        new PortfolioAggregatorService(
            WebClient.builder().build(),
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
}
