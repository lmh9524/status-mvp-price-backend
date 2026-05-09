package io.statusmvp.pricebackend.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import io.statusmvp.pricebackend.service.UniswapProxyService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@WebFluxTest(controllers = UniswapProxyController.class)
class UniswapProxyControllerTest {
  @Autowired private WebTestClient webTestClient;

  @MockBean private UniswapProxyService uniswap;

  @Test
  void checkApprovalSanitizesBodyAndForwardsToService() {
    given(uniswap.checkApproval(any(), any())).willReturn(Mono.just(ResponseEntity.ok("{}")));

    webTestClient
        .post()
        .uri("/api/v1/evm/uniswap/check_approval")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            """
            {
              "walletAddress": "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045",
              "token": "0xA0b86991c6218b36c1d19d4a2e9eb0ce3606eb48",
              "amount": "1000000",
              "chainId": 196,
              "urgency": "fast"
            }
            """)
        .exchange()
        .expectStatus()
        .isOk();

    ArgumentCaptor<JsonNode> bodyCaptor = ArgumentCaptor.forClass(JsonNode.class);
    verify(uniswap).checkApproval(bodyCaptor.capture(), eq(""));
    JsonNode forwarded = bodyCaptor.getValue();
    org.junit.jupiter.api.Assertions.assertEquals(
        "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045", forwarded.path("walletAddress").asText());
    org.junit.jupiter.api.Assertions.assertEquals(
        "0xA0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", forwarded.path("token").asText());
    org.junit.jupiter.api.Assertions.assertEquals("1000000", forwarded.path("amount").asText());
    org.junit.jupiter.api.Assertions.assertEquals(196, forwarded.path("chainId").asInt());
    org.junit.jupiter.api.Assertions.assertTrue(forwarded.path("includeGasInfo").asBoolean());
    org.junit.jupiter.api.Assertions.assertEquals("fast", forwarded.path("urgency").asText());
  }

  @Test
  void quoteRejectsCrossChainRequest() {
    webTestClient
        .post()
        .uri("/api/v1/evm/uniswap/quote")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            """
            {
              "amount": "1000000",
              "tokenInChainId": 196,
              "tokenOutChainId": 56,
              "tokenIn": "0xA0b86991c6218b36c1d19d4a2e9eb0ce3606eb48",
              "tokenOut": "0x4200000000000000000000000000000000000006",
              "swapper": "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045"
            }
            """)
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void swapRejectsNonClassicRouting() {
    webTestClient
        .post()
        .uri("/api/v1/evm/uniswap/swap")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            """
            {
              "routing": "UNISWAPX",
              "quote": {
                "chainId": 196
              }
            }
            """)
        .exchange()
        .expectStatus()
        .isBadRequest();
  }
}
