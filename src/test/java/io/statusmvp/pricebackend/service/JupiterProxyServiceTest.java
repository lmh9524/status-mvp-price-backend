package io.statusmvp.pricebackend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;

class JupiterProxyServiceTest {

  private static JupiterProxyService service(WebClient webClient, long timeoutMs) {
    return new JupiterProxyService(
        webClient, null, "https://lite-api.jup.ag/swap/v1", timeoutMs, 60, 600);
  }

  private static WebClient failingClient(Throwable upstreamError) {
    return WebClient.builder()
        .exchangeFunction(
            request ->
                Mono.error(
                    new WebClientRequestException(
                        upstreamError,
                        HttpMethod.GET,
                        URI.create("https://lite-api.jup.ag/swap/v1/quote"),
                        new HttpHeaders())))
        .build();
  }

  @Test
  void dnsFailureIsReportedAsDnsErrorNotTimeout() {
    JupiterProxyService service =
        service(failingClient(new UnknownHostException("lite-api.jup.ag")), 3000);

    ResponseEntity<String> response = service.quote(null, null).block();

    assertNotNull(response);
    assertEquals(502, response.getStatusCode().value());
    assertEquals(
        "{\"error\":\"upstream dns resolution failed\",\"cause\":\"UnknownHostException\"}",
        response.getBody());
  }

  @Test
  void connectionFailureIsReportedAsConnectionErrorNotTimeout() {
    JupiterProxyService service =
        service(failingClient(new ConnectException("Connection refused")), 3000);

    ResponseEntity<String> response = service.quote(null, null).block();

    assertNotNull(response);
    assertEquals(502, response.getStatusCode().value());
    assertEquals(
        "{\"error\":\"upstream connection failed\",\"cause\":\"ConnectException\"}",
        response.getBody());
  }

  @Test
  void timeoutKeepsLegacy504TimeoutResponse() {
    WebClient neverResponding =
        WebClient.builder().exchangeFunction(request -> Mono.never()).build();
    JupiterProxyService service = service(neverResponding, 1000);

    ResponseEntity<String> response = service.quote(null, null).block();

    assertNotNull(response);
    assertEquals(504, response.getStatusCode().value());
    assertEquals("{\"error\":\"upstream timeout\"}", response.getBody());
  }

  @Test
  void unexpectedErrorIsReportedAsUpstreamRequestFailedWithCause() {
    JupiterProxyService service =
        service(failingClient(new IllegalStateException("boom")), 3000);

    ResponseEntity<String> response = service.swap(null, null).block();

    assertNotNull(response);
    assertEquals(502, response.getStatusCode().value());
    assertEquals(
        "{\"error\":\"upstream request failed\",\"cause\":\"IllegalStateException\"}",
        response.getBody());
  }
}
