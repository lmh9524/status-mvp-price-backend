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
    given(legalProperties.getAppName()).willReturn("Veil Wallet");
    given(legalProperties.getEntityName()).willReturn("VeilLabs");
    given(legalProperties.getContactEmail()).willReturn("veillabs.wallet@gmail.com");
    given(legalProperties.getContactAddress()).willReturn("香港九龙区");
    given(legalProperties.getEffectiveDate()).willReturn("2026-02-20");
    given(legalProperties.getGoverningLawZh()).willReturn("香港特别行政区法律");

    webTestClient
        .get()
        .uri("/terms")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .contentType("text/html;charset=UTF-8")
        .expectBody(String.class)
        .value(
            body ->
                org.assertj.core.api.Assertions.assertThat(body)
                    .contains("Terms of Service")
                    .contains("VeilLabs")
                    .contains("veillabs.wallet@gmail.com")
                    .contains("香港特别行政区法律"));
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

  @Test
  void privacyReturnsFallbackHtmlWhenUrlMissing() {
    given(legalProperties.getPrivacyUrl()).willReturn("");
    given(legalProperties.getEntityName()).willReturn("VeilLabs");
    given(legalProperties.getContactEmail()).willReturn("veillabs.wallet@gmail.com");

    webTestClient
        .get()
        .uri("/privacy")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .contentType("text/html;charset=UTF-8")
        .expectBody(String.class)
        .value(
            body ->
                org.assertj.core.api.Assertions.assertThat(body)
                    .contains("Privacy Policy")
                    .contains("VeilLabs")
                    .contains("veillabs.wallet@gmail.com"));
  }
}
