package io.statusmvp.pricebackend.controller;

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
    return legalPage(
        legalProperties.getTermsUrl(),
        "Terms of Service",
        "Please configure LEGAL_TERMS_URL in backend env.");
  }

  @GetMapping(value = "/privacy", produces = MediaType.TEXT_HTML_VALUE)
  public ResponseEntity<String> privacy() {
    return legalPage(
        legalProperties.getPrivacyUrl(),
        "Privacy Policy",
        "Please configure LEGAL_PRIVACY_URL in backend env.");
  }

  private ResponseEntity<String> legalPage(String redirectUrl, String title, String fallbackMessage) {
    String url = redirectUrl == null ? "" : redirectUrl.trim();
    if (!url.isEmpty()) {
      try {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
      } catch (IllegalArgumentException ignored) {
      }
    }

    String html =
        "<!doctype html><html><head><meta charset=\"utf-8\"><title>"
            + escapeHtml(title)
            + "</title></head><body><h1>"
            + escapeHtml(title)
            + "</h1><p>"
            + escapeHtml(fallbackMessage)
            + "</p></body></html>";
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
        .body(html);
  }

  private static String escapeHtml(String input) {
    if (input == null) return "";
    return input
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }
}
