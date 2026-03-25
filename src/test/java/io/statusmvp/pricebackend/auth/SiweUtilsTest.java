package io.statusmvp.pricebackend.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

public class SiweUtilsTest {

  @Test
  void parseAndRecoverAddress_roundTrip() throws Exception {
    ECKeyPair keyPair = Keys.createEcKeyPair();
    String address = Credentials.create(keyPair).getAddress().toLowerCase();

    String domain = "vex.veilx.global";
    String uri = "https://vex.veilx.global";
    String nonce = "test-nonce-123";
    String issuedAt = Instant.parse("2026-03-05T00:00:00Z").toString();
    String expirationTime = Instant.parse("2026-03-05T00:10:00Z").toString();
    String statement = "VeilWallet wants you to sign in.";

    String message =
        domain
            + " wants you to sign in with your Ethereum account:\n"
            + address
            + "\n\n"
            + statement
            + "\n\n"
            + "URI: "
            + uri
            + "\n"
            + "Version: 1\n"
            + "Chain ID: 1\n"
            + "Nonce: "
            + nonce
            + "\n"
            + "Issued At: "
            + issuedAt
            + "\n"
            + "Expiration Time: "
            + expirationTime;

    SiweUtils.ParsedSiwe parsed = SiweUtils.parseMessage(message);
    assertNotNull(parsed);
    assertEquals(domain, parsed.domain());
    assertEquals(address, parsed.address().toLowerCase());
    assertEquals(uri, parsed.uri());
    assertEquals(1, parsed.chainId());
    assertEquals(nonce, parsed.nonce());
    assertEquals(Instant.parse(issuedAt), parsed.issuedAt());
    assertEquals(Instant.parse(expirationTime), parsed.expirationTime());

    Sign.SignatureData sig =
        Sign.signPrefixedMessage(message.getBytes(StandardCharsets.UTF_8), keyPair);
    String sigHex =
        "0x"
            + Numeric.toHexStringNoPrefix(sig.getR())
            + Numeric.toHexStringNoPrefix(sig.getS())
            + Numeric.toHexStringNoPrefix(sig.getV());

    String recovered = SiweUtils.recoverAddress(message, sigHex);
    assertEquals(address, recovered);
  }

  @Test
  void recoverAddress_rejectsInvalidSignature() {
    assertThrows(
        IllegalArgumentException.class,
        () -> SiweUtils.recoverAddress("hello", "0x1234"));
  }
}
