package io.statusmvp.pricebackend.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

@Service
public class AuthJwtService {
  private final AuthProperties authProperties;
  private final RSAPrivateKey web3AuthPrivateKey;
  private final RSAPublicKey web3AuthPublicKey;
  private final SecretKey appJwtSecret;

  public AuthJwtService(AuthProperties authProperties) {
    this.authProperties = authProperties;
    KeyPair pair = loadOrGenerateRsaKeyPair(authProperties.getWeb3auth().getPrivateKeyPem());
    this.web3AuthPrivateKey = (RSAPrivateKey) pair.getPrivate();
    this.web3AuthPublicKey = (RSAPublicKey) pair.getPublic();
    byte[] secretBytes = authProperties.getAppJwt().getSecret().getBytes(StandardCharsets.UTF_8);
    if (secretBytes.length < 32) {
      throw new IllegalStateException("AUTH_APP_JWT_SECRET must be at least 32 bytes");
    }
    this.appJwtSecret = new SecretKeySpec(secretBytes, "HmacSHA256");
  }

  public String issueWeb3AuthJwt(String providerSub, String nonce, long ttlSeconds) {
    try {
      Instant now = Instant.now();
      SignedJWT jwt =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.RS256)
                  .type(JOSEObjectType.JWT)
                  .keyID(authProperties.getWeb3auth().getKeyId())
                  .build(),
              new JWTClaimsSet.Builder()
                  .issuer(authProperties.getWeb3auth().getIssuer())
                  .audience(authProperties.getWeb3auth().getAudience())
                  .subject(providerSub)
                  .issueTime(Date.from(now))
                  .expirationTime(Date.from(now.plusSeconds(Math.max(30, ttlSeconds))))
                  .jwtID(UUID.randomUUID().toString())
                  .claim("nonce", nonce == null || nonce.isBlank() ? UUID.randomUUID().toString() : nonce)
                  .build());
      jwt.sign(new RSASSASigner(web3AuthPrivateKey));
      return jwt.serialize();
    } catch (JOSEException e) {
      throw new AuthException(AuthErrorCode.BAD_REQUEST, "failed to sign web3auth jwt", 500);
    }
  }

  public String issueAccessToken(String walletSub, long ttlSeconds) {
    try {
      Instant now = Instant.now();
      SignedJWT jwt =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(),
              new JWTClaimsSet.Builder()
                  .issuer(authProperties.getAppJwt().getIssuer())
                  .audience(authProperties.getAppJwt().getAudience())
                  .subject(walletSub)
                  .issueTime(Date.from(now))
                  .expirationTime(Date.from(now.plusSeconds(Math.max(30, ttlSeconds))))
                  .jwtID(UUID.randomUUID().toString())
                  .claim("tokenType", "access")
                  .build());
      jwt.sign(new MACSigner(appJwtSecret.getEncoded()));
      return jwt.serialize();
    } catch (JOSEException e) {
      throw new AuthException(AuthErrorCode.BAD_REQUEST, "failed to sign access token", 500);
    }
  }

  public AccessTokenClaims verifyAccessToken(String token) {
    try {
      SignedJWT jwt = SignedJWT.parse(token);
      JWSVerifier verifier = new MACVerifier(appJwtSecret.getEncoded());
      if (!jwt.verify(verifier)) {
        throw new AuthException(AuthErrorCode.ACCESS_TOKEN_INVALID, "invalid access token", 401);
      }
      JWTClaimsSet claims = jwt.getJWTClaimsSet();
      String expectedIssuer = authProperties.getAppJwt().getIssuer();
      String expectedAudience = authProperties.getAppJwt().getAudience();
      if (claims.getIssuer() == null || !claims.getIssuer().equals(expectedIssuer)) {
        throw new AuthException(AuthErrorCode.ACCESS_TOKEN_INVALID, "invalid access token", 401);
      }
      if (claims.getAudience() == null || !claims.getAudience().contains(expectedAudience)) {
        throw new AuthException(AuthErrorCode.ACCESS_TOKEN_INVALID, "invalid access token", 401);
      }
      Date expires = claims.getExpirationTime();
      if (expires == null || expires.before(new Date())) {
        throw new AuthException(AuthErrorCode.ACCESS_TOKEN_EXPIRED, "access token expired", 401);
      }
      String subject = claims.getSubject();
      if (subject == null || subject.isBlank()) {
        throw new AuthException(AuthErrorCode.ACCESS_TOKEN_INVALID, "invalid access token", 401);
      }
      String tokenType = (String) claims.getClaim("tokenType");
      if (!"access".equals(tokenType)) {
        throw new AuthException(AuthErrorCode.ACCESS_TOKEN_INVALID, "invalid access token type", 401);
      }
      String jti = claims.getJWTID();
      return new AccessTokenClaims(subject, jti, expires.toInstant().getEpochSecond());
    } catch (AuthException e) {
      throw e;
    } catch (Exception e) {
      throw new AuthException(AuthErrorCode.ACCESS_TOKEN_INVALID, "invalid access token", 401);
    }
  }

  public Map<String, Object> web3AuthJwksJson() {
    RSAKey key =
        new RSAKey.Builder(web3AuthPublicKey)
            .algorithm(JWSAlgorithm.RS256)
            .keyID(authProperties.getWeb3auth().getKeyId())
            .build();
    return new JWKSet(key).toJSONObject();
  }

  public String sha256Hex(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] out = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (byte b : out) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static KeyPair loadOrGenerateRsaKeyPair(String pem) {
    try {
      if (pem != null && !pem.isBlank()) {
        RSAPrivateKey privateKey = parsePrivateKey(pem);
        RSAPublicKey publicKey = derivePublicKey(privateKey);
        return new KeyPair(publicKey, privateKey);
      }
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (Exception e) {
      throw new IllegalStateException("failed to initialize rsa key pair", e);
    }
  }

  private static RSAPrivateKey parsePrivateKey(String pem) throws Exception {
    String withNewlines = pem.replace("\\n", "\n").trim();
    boolean pkcs1 = withNewlines.contains("BEGIN RSA PRIVATE KEY");
    String normalized =
        withNewlines
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replaceAll("\\s+", "");
    byte[] der = Base64.getDecoder().decode(normalized);
    if (pkcs1) {
      der = pkcs1ToPkcs8(der);
    }
    PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
    KeyFactory kf = KeyFactory.getInstance("RSA");
    return (RSAPrivateKey) kf.generatePrivate(spec);
  }

  private static RSAPublicKey derivePublicKey(RSAPrivateKey privateKey) throws Exception {
    if (privateKey instanceof RSAPrivateCrtKey crtKey) {
      RSAPublicKeySpec publicKeySpec =
          new RSAPublicKeySpec(crtKey.getModulus(), crtKey.getPublicExponent());
      KeyFactory keyFactory = KeyFactory.getInstance("RSA");
      return (RSAPublicKey) keyFactory.generatePublic(publicKeySpec);
    }
    BigInteger exponent = BigInteger.valueOf(65537L);
    RSAPublicKeySpec publicKeySpec = new RSAPublicKeySpec(privateKey.getModulus(), exponent);
    KeyFactory keyFactory = KeyFactory.getInstance("RSA");
    return (RSAPublicKey) keyFactory.generatePublic(publicKeySpec);
  }

  private static byte[] pkcs1ToPkcs8(byte[] pkcs1Der) {
    // Wrap PKCS#1 RSAPrivateKey DER bytes into PKCS#8 PrivateKeyInfo for RSA.
    byte[] version = derInteger(new byte[] {0x00});
    byte[] rsaOid =
        derOid(new byte[] {0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x01});
    byte[] algId = derSequence(concat(rsaOid, derNull()));
    byte[] privateKeyOctet = derOctetString(pkcs1Der);
    return derSequence(concat(version, algId, privateKeyOctet));
  }

  private static byte[] derSequence(byte[] content) {
    return derWrap(0x30, content);
  }

  private static byte[] derInteger(byte[] content) {
    return derWrap(0x02, content);
  }

  private static byte[] derOctetString(byte[] content) {
    return derWrap(0x04, content);
  }

  private static byte[] derOid(byte[] content) {
    return derWrap(0x06, content);
  }

  private static byte[] derNull() {
    return new byte[] {0x05, 0x00};
  }

  private static byte[] derWrap(int tag, byte[] content) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(tag);
    writeDerLength(out, content.length);
    out.writeBytes(content);
    return out.toByteArray();
  }

  private static void writeDerLength(ByteArrayOutputStream out, int length) {
    if (length < 0) throw new IllegalArgumentException("length < 0");
    if (length < 128) {
      out.write(length);
      return;
    }
    int numBytes = 0;
    int tmp = length;
    while (tmp > 0) {
      numBytes++;
      tmp >>= 8;
    }
    out.write(0x80 | numBytes);
    for (int i = numBytes - 1; i >= 0; i--) {
      out.write((length >> (8 * i)) & 0xFF);
    }
  }

  private static byte[] concat(byte[]... parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] part : parts) {
      if (part == null || part.length == 0) continue;
      out.writeBytes(part);
    }
    return out.toByteArray();
  }

  public record AccessTokenClaims(String walletSub, String jti, long expEpochSeconds) {}
}
