package io.statusmvp.pricebackend.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class XOAuthStateTokensTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String SECRET = "0123456789abcdef0123456789abcdef"; // 32 bytes

  @Test
  void issueAndParseRoundTrip() {
    long nowMs = 1_700_000_000_000L;
    XOAuthStateTokens.Issued issued =
        XOAuthStateTokens.issue(MAPPER, SECRET, "veilwallet://openlogin", 600, nowMs);
    assertNotNull(issued);
    assertTrue(XOAuthStateTokens.looksLikeToken(issued.state()));
    assertTrue(issued.codeVerifier().length() >= 43);

    XOAuthStateTokens.Parsed parsed =
        XOAuthStateTokens.parseAndVerify(MAPPER, SECRET, issued.state(), nowMs + 5_000);
    assertNotNull(parsed);
    assertEquals("veilwallet://openlogin", parsed.appRedirectUri());
    assertEquals(issued.codeVerifier(), parsed.codeVerifier());
    assertTrue(parsed.expiresAtMs() > nowMs);
    assertTrue(parsed.issuedAtMs() > 0);
  }

  @Test
  void parseRejectsTamperedSignature() {
    long nowMs = 1_700_000_000_000L;
    XOAuthStateTokens.Issued issued =
        XOAuthStateTokens.issue(MAPPER, SECRET, "veilwallet://openlogin", 600, nowMs);
    String tampered = issued.state().substring(0, issued.state().length() - 1) + "A";
    assertNull(XOAuthStateTokens.parseAndVerify(MAPPER, SECRET, tampered, nowMs));
  }

  @Test
  void parseRejectsExpiredToken() {
    long nowMs = 1_700_000_000_000L;
    XOAuthStateTokens.Issued issued =
        XOAuthStateTokens.issue(MAPPER, SECRET, "veilwallet://openlogin", 1, nowMs);
    assertNull(XOAuthStateTokens.parseAndVerify(MAPPER, SECRET, issued.state(), nowMs + 5_000));
  }
}

