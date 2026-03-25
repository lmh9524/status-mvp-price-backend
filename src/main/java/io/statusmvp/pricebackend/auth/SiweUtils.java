package io.statusmvp.pricebackend.auth;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

public final class SiweUtils {
  private SiweUtils() {}

  public record ParsedSiwe(
      String domain,
      String address,
      String uri,
      long chainId,
      String nonce,
      Instant issuedAt,
      Instant expirationTime) {}

  private static final String DOMAIN_SUFFIX = " wants you to sign in with your Ethereum account:";

  public static ParsedSiwe parseMessage(String message) {
    String raw = message == null ? "" : message.trim();
    if (raw.isBlank()) {
      throw new IllegalArgumentException("missing siwe message");
    }

    int idx = raw.indexOf(DOMAIN_SUFFIX);
    if (idx <= 0) {
      throw new IllegalArgumentException("invalid siwe message");
    }
    String domain = raw.substring(0, idx).trim();
    int addrLineStart = raw.indexOf('\n', idx + DOMAIN_SUFFIX.length());
    if (addrLineStart < 0) {
      throw new IllegalArgumentException("invalid siwe message");
    }
    int addrLineEnd = raw.indexOf('\n', addrLineStart + 1);
    if (addrLineEnd < 0) {
      throw new IllegalArgumentException("invalid siwe message");
    }
    String address = raw.substring(addrLineStart + 1, addrLineEnd).trim();

    Map<String, String> fields = new HashMap<>();
    for (String line : raw.split("\n")) {
      if (line == null) continue;
      String trimmed = line.trim();
      int sep = trimmed.indexOf(':');
      if (sep <= 0) continue;
      String k = trimmed.substring(0, sep).trim().toLowerCase(Locale.ROOT);
      String v = trimmed.substring(sep + 1).trim();
      if (!v.isBlank()) {
        fields.put(k, v);
      }
    }

    String uri = fields.getOrDefault("uri", "");
    String nonce = fields.getOrDefault("nonce", "");
    long chainId = parseLongOrZero(fields.get("chain id"));
    Instant issuedAt = parseInstant(fields.get("issued at"));
    Instant expirationTime = parseInstant(fields.get("expiration time"));

    if (domain.isBlank()
        || address.isBlank()
        || uri.isBlank()
        || nonce.isBlank()
        || chainId <= 0
        || issuedAt == null
        || expirationTime == null) {
      throw new IllegalArgumentException("invalid siwe message");
    }

    return new ParsedSiwe(domain, address, uri, chainId, nonce, issuedAt, expirationTime);
  }

  public static String recoverAddress(String message, String signatureHex) {
    String sig = signatureHex == null ? "" : signatureHex.trim();
    if (sig.startsWith("0x") || sig.startsWith("0X")) {
      sig = sig.substring(2);
    }
    if (sig.length() != 130) {
      throw new IllegalArgumentException("invalid signature");
    }
    byte[] sigBytes = Numeric.hexStringToByteArray(sig);
    byte v = sigBytes[64];
    if (v < 27) {
      v = (byte) (v + 27);
    }
    byte[] r = new byte[32];
    byte[] s = new byte[32];
    System.arraycopy(sigBytes, 0, r, 0, 32);
    System.arraycopy(sigBytes, 32, s, 0, 32);
    Sign.SignatureData signatureData = new Sign.SignatureData(v, r, s);

    try {
      BigInteger pubKey =
          Sign.signedPrefixedMessageToKey(message.getBytes(StandardCharsets.UTF_8), signatureData);
      String recovered = "0x" + Keys.getAddress(pubKey);
      return recovered.toLowerCase(Locale.ROOT);
    } catch (Exception e) {
      throw new IllegalArgumentException("invalid signature");
    }
  }

  private static long parseLongOrZero(String raw) {
    if (raw == null) return 0;
    try {
      return Long.parseLong(raw.trim());
    } catch (Exception e) {
      return 0;
    }
  }

  private static Instant parseInstant(String raw) {
    if (raw == null) return null;
    try {
      return Instant.parse(raw.trim());
    } catch (DateTimeParseException e) {
      return null;
    }
  }
}

