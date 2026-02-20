package io.statusmvp.pricebackend.controller;

import io.statusmvp.pricebackend.legal.LegalPageRenderer;
import io.statusmvp.pricebackend.legal.LegalProperties;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LegalController {
  private final LegalProperties legalProperties;

  public LegalController(LegalProperties legalProperties) {
    this.legalProperties = legalProperties;
  }

  @GetMapping(value = "/terms", produces = MediaType.TEXT_HTML_VALUE)
  public ResponseEntity<String> terms() {
    return legalPage(legalProperties.getTermsUrl(), LegalPageRenderer.renderTermsHtml(legalProperties));
  }

  @GetMapping(value = "/privacy", produces = MediaType.TEXT_HTML_VALUE)
  public ResponseEntity<String> privacy() {
    return legalPage(
        legalProperties.getPrivacyUrl(), LegalPageRenderer.renderPrivacyHtml(legalProperties));
  }

  private ResponseEntity<String> legalPage(String redirectUrl, String fallbackHtml) {
    String url = redirectUrl == null ? "" : redirectUrl.trim();
    if (!url.isEmpty()) {
      try {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
      } catch (IllegalArgumentException ignored) {
      }
    }
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
        .body(fallbackHtml);
  }
}
