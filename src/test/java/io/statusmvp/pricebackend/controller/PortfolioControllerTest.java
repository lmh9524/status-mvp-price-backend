package io.statusmvp.pricebackend.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import io.statusmvp.pricebackend.model.PortfolioChainSummary;
import io.statusmvp.pricebackend.model.PortfolioSnapshot;
import io.statusmvp.pricebackend.service.PortfolioAggregatorService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.reactive.server.WebTestClient;

@WebFluxTest(controllers = PortfolioController.class)
class PortfolioControllerTest {
  private static final String VALID_ADDRESS = "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045";

  @Autowired private WebTestClient webTestClient;

  @MockBean private PortfolioAggregatorService portfolio;

  @Test
  void returnsPortfolioSnapshot() {
    PortfolioSnapshot snapshot =
        new PortfolioSnapshot(
            VALID_ADDRESS,
            1739011200000L,
            1234.56,
            List.of(
                new PortfolioChainSummary(
                    56, "bsc", "BNB", "1.2", 300.0, 360.0, 12.0, 372.0, 3, 2)));
    given(portfolio.parseChainIds("56,8453")).willReturn(List.of(56, 8453));
    given(portfolio.getPortfolio(VALID_ADDRESS, List.of(56, 8453))).willReturn(snapshot);

    webTestClient
        .get()
        .uri(
            uriBuilder ->
                uriBuilder
                    .path("/api/v1/portfolio")
                    .queryParam("address", VALID_ADDRESS)
                    .queryParam("chainIds", "56,8453")
                    .build())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.address")
        .isEqualTo(VALID_ADDRESS)
        .jsonPath("$.totalUsd")
        .isEqualTo(1234.56)
        .jsonPath("$.chains.length()")
        .isEqualTo(1);

    verify(portfolio).parseChainIds("56,8453");
    verify(portfolio).getPortfolio(VALID_ADDRESS, List.of(56, 8453));
  }

  @Test
  void missingAddressReturnsBadRequest() {
    webTestClient.get().uri("/api/v1/portfolio").exchange().expectStatus().isBadRequest();
  }

  @Test
  void serviceValidationErrorBubblesAsServerError() {
    given(portfolio.parseChainIds(any())).willReturn(List.of(1));
    given(portfolio.getPortfolio(eq(VALID_ADDRESS), any()))
        .willThrow(new IllegalArgumentException("address must be a valid EVM address"));

    webTestClient
        .get()
        .uri(
            uriBuilder ->
                uriBuilder.path("/api/v1/portfolio").queryParam("address", VALID_ADDRESS).build())
        .exchange()
        .expectStatus()
        .is5xxServerError();
  }
}

