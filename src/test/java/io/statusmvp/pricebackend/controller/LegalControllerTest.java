package io.statusmvp.pricebackend.controller;

import static org.mockito.BDDMockito.given;

import io.statusmvp.pricebackend.legal.LegalProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

@WebFluxTest(controllers = LegalController.class)
class LegalControllerTest {
  @Autowired private WebTestClient webTestClient;

  @MockBean private LegalProperties legalProperties;

  @Test
  void termsReturnsRedirectWhenConfigured() {
    given(legalProperties.getTermsUrl()).willReturn("https://vex.veilx.global/legal/terms");

    webTestClient
        .get()
        .uri("/terms")
        .exchange()
        .expectStatus()
        .isFound()
        .expectHeader()
        .valueEquals(HttpHeaders.LOCATION, "https://vex.veilx.global/legal/terms");
  }

  @Test
  void termsReturnsFallbackHtmlWhenUrlMissing() {
    given(legalProperties.getTermsUrl()).willReturn("");

    webTestClient
        .get()
        .uri("/terms")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .contentType("text/html;charset=UTF-8")
        .expectBody(String.class)
        .value(body -> org.assertj.core.api.Assertions.assertThat(body).contains("Terms of Service"));
  }

  @Test
  void privacyReturnsRedirectWhenConfigured() {
    given(legalProperties.getPrivacyUrl()).willReturn("https://vex.veilx.global/legal/privacy");

    webTestClient
        .get()
        .uri("/privacy")
        .exchange()
        .expectStatus()
        .isFound()
        .expectHeader()
        .valueEquals(HttpHeaders.LOCATION, "https://vex.veilx.global/legal/privacy");
  }
}
