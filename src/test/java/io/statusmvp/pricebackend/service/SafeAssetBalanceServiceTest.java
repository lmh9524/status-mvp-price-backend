package io.statusmvp.pricebackend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.statusmvp.pricebackend.client.SafeTxServiceClient;
import io.statusmvp.pricebackend.model.PortfolioAssetSnapshotV2;
import io.statusmvp.pricebackend.model.PortfolioSnapshotV2;
import io.statusmvp.pricebackend.model.safe.SafeAssetBalanceItem;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;

class SafeAssetBalanceServiceTest {
  private static final String SAFE = "0xb3dd73d914c79019f091b5aedc37aad2e0000000";
  private static final String VEILX = "0x75999a8ee8f52666c4d76f6d0f8e7e914334762a";
  private static final String UNKNOWN = "0x1111111111111111111111111111111111111111";

  @Test
  void trustedModeAddsKnownVeilTokenFromPortfolio() {
    SafeAssetBalanceService service = newService(portfolioAsset(VEILX, "VEILX"));

    List<SafeAssetBalanceItem> balances = service.listBalances(56, SAFE, true).block();

    assertEquals(2, balances.size());
    assertTrue(balances.stream().anyMatch(item -> item.tokenAddress() == null));
    assertTrue(
        balances.stream()
            .anyMatch(item -> VEILX.equals(item.tokenAddress()) && Boolean.TRUE.equals(item.trusted())));
  }

  @Test
  void allModeAddsUnknownPortfolioToken() {
    SafeAssetBalanceService service = newService(portfolioAsset(UNKNOWN, "SMALL"));

    List<SafeAssetBalanceItem> balances = service.listBalances(56, SAFE, false).block();

    assertEquals(2, balances.size());
    assertTrue(balances.stream().anyMatch(item -> UNKNOWN.equals(item.tokenAddress())));
  }

  @Test
  void trustedModeHidesUnknownPortfolioToken() {
    SafeAssetBalanceService service = newService(portfolioAsset(UNKNOWN, "SMALL"));

    List<SafeAssetBalanceItem> balances = service.listBalances(56, SAFE, true).block();

    assertEquals(1, balances.size());
    assertTrue(balances.stream().noneMatch(item -> UNKNOWN.equals(item.tokenAddress())));
  }

  private static SafeAssetBalanceService newService(PortfolioAssetSnapshotV2 portfolioAsset) {
    SafeTxServiceClient safeTx = mock(SafeTxServiceClient.class);
    when(safeTx.get(eq("bnb"), any(), any()))
        .thenReturn(
            Mono.just(
                ResponseEntity.ok(
                    """
                    [
                      {
                        "tokenAddress": null,
                        "token": null,
                        "balance": "2500000000000000",
                        "trusted": true
                      }
                    ]
                    """)));

    PortfolioAggregatorService portfolio = mock(PortfolioAggregatorService.class);
    when(portfolio.getPortfolioSnapshotV2(eq(SAFE), eq(List.of(56)), eq("usd"), eq(0d), eq(false), eq(1000), eq(true)))
        .thenReturn(
            new PortfolioSnapshotV2(
                SAFE,
                1L,
                "usd",
                0d,
                Map.of(),
                List.of(portfolioAsset)));

    return new SafeAssetBalanceService(safeTx, portfolio, new ObjectMapper());
  }

  private static PortfolioAssetSnapshotV2 portfolioAsset(String contractAddress, String symbol) {
    return new PortfolioAssetSnapshotV2(
        56,
        "bsc",
        false,
        contractAddress,
        symbol,
        symbol,
        18,
        "1000000000000000000",
        "1",
        null,
        null,
        null,
        null,
        1L);
  }
}
