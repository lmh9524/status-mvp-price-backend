package io.statusmvp.pricebackend.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class TelegramOidcStateTokensTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String SECRET = "0123456789abcdef0123456789abcdef";

  @Test
  void issueAndParseRoundTrip() {
    long nowMs = 1_700_000_000_000L;
    TelegramOidcStateTokens.Issued issued =
        TelegramOidcStateTokens.issue(MAPPER, SECRET, "veilwallet://openlogin", 600, nowMs);
    assertNotNull(issued);
    assertTrue(TelegramOidcStateTokens.looksLikeToken(issued.state()));
    assertTrue(issued.codeVerifier().length() >= 43);

    TelegramOidcStateTokens.Parsed parsed =
        TelegramOidcStateTokens.parseAndVerify(MAPPER, SECRET, issued.state(), nowMs + 5_000);
    assertNotNull(parsed);
    assertEquals("veilwallet://openlogin", parsed.appRedirectUri());
    assertEquals(issued.codeVerifier(), parsed.codeVerifier());
    assertTrue(parsed.expiresAtMs() > nowMs);
    assertTrue(parsed.issuedAtMs() > 0);
  }

  @Test
  void parseRejectsTamperedSignature() {
    long nowMs = 1_700_000_000_000L;
    TelegramOidcStateTokens.Issued issued =
        TelegramOidcStateTokens.issue(MAPPER, SECRET, "veilwallet://openlogin", 600, nowMs);
    String[] parts = issued.state().split("\\.", 2);
    String originalSignature = parts[1];
    char first = originalSignature.charAt(0);
    char replacement = first == 'A' ? 'B' : 'A';
    String tamperedSignature = replacement + originalSignature.substring(1);
    String tampered = parts[0] + "." + tamperedSignature;
    assertNull(TelegramOidcStateTokens.parseAndVerify(MAPPER, SECRET, tampered, nowMs));
  }

  @Test
  void parseRejectsExpiredToken() {
    long nowMs = 1_700_000_000_000L;
    TelegramOidcStateTokens.Issued issued =
        TelegramOidcStateTokens.issue(MAPPER, SECRET, "veilwallet://openlogin", 1, nowMs);
    assertNull(TelegramOidcStateTokens.parseAndVerify(MAPPER, SECRET, issued.state(), nowMs + 5_000));
  }
}
