package io.statusmvp.pricebackend.auth;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties {
  private boolean enabled = true;
  private long oneTimeCodeTtlSeconds = 60;
  private long oauthStateTtlSeconds = 600;
  private long web3authJwtTtlSeconds = 300;
  private long accessTokenTtlSeconds = 900;
  private long refreshTokenTtlSeconds = 2_592_000;
  private boolean xEnabled = true;
  private boolean tgEnabled = true;
  private boolean syncEnabled = true;
  private boolean bindEnabled = true;
  private boolean metricsEnabled = true;
  private String appRedirectAllowlist = "";

  private Web3Auth web3auth = new Web3Auth();
  private AppJwt appJwt = new AppJwt();
  private X x = new X();
  private Tg tg = new Tg();
  private Risk risk = new Risk();

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public long getOneTimeCodeTtlSeconds() {
    return oneTimeCodeTtlSeconds;
  }

  public void setOneTimeCodeTtlSeconds(long oneTimeCodeTtlSeconds) {
    this.oneTimeCodeTtlSeconds = oneTimeCodeTtlSeconds;
  }

  public long getOauthStateTtlSeconds() {
    return oauthStateTtlSeconds;
  }

  public void setOauthStateTtlSeconds(long oauthStateTtlSeconds) {
    this.oauthStateTtlSeconds = oauthStateTtlSeconds;
  }

  public long getWeb3authJwtTtlSeconds() {
    return web3authJwtTtlSeconds;
  }

  public void setWeb3authJwtTtlSeconds(long web3authJwtTtlSeconds) {
    this.web3authJwtTtlSeconds = web3authJwtTtlSeconds;
  }

  public long getAccessTokenTtlSeconds() {
    return accessTokenTtlSeconds;
  }

  public void setAccessTokenTtlSeconds(long accessTokenTtlSeconds) {
    this.accessTokenTtlSeconds = accessTokenTtlSeconds;
  }

  public long getRefreshTokenTtlSeconds() {
    return refreshTokenTtlSeconds;
  }

  public void setRefreshTokenTtlSeconds(long refreshTokenTtlSeconds) {
    this.refreshTokenTtlSeconds = refreshTokenTtlSeconds;
  }

  public boolean isXEnabled() {
    return xEnabled;
  }

  public void setXEnabled(boolean xEnabled) {
    this.xEnabled = xEnabled;
  }

  public boolean isTgEnabled() {
    return tgEnabled;
  }

  public void setTgEnabled(boolean tgEnabled) {
    this.tgEnabled = tgEnabled;
  }

  public boolean isSyncEnabled() {
    return syncEnabled;
  }

  public void setSyncEnabled(boolean syncEnabled) {
    this.syncEnabled = syncEnabled;
  }

  public boolean isBindEnabled() {
    return bindEnabled;
  }

  public void setBindEnabled(boolean bindEnabled) {
    this.bindEnabled = bindEnabled;
  }

  public boolean isMetricsEnabled() {
    return metricsEnabled;
  }

  public void setMetricsEnabled(boolean metricsEnabled) {
    this.metricsEnabled = metricsEnabled;
  }

  public String getAppRedirectAllowlist() {
    return appRedirectAllowlist;
  }

  public void setAppRedirectAllowlist(String appRedirectAllowlist) {
    this.appRedirectAllowlist = appRedirectAllowlist;
  }

  public Web3Auth getWeb3auth() {
    return web3auth;
  }

  public void setWeb3auth(Web3Auth web3auth) {
    this.web3auth = web3auth;
  }

  public AppJwt getAppJwt() {
    return appJwt;
  }

  public void setAppJwt(AppJwt appJwt) {
    this.appJwt = appJwt;
  }

  public X getX() {
    return x;
  }

  public void setX(X x) {
    this.x = x;
  }

  public Tg getTg() {
    return tg;
  }

  public void setTg(Tg tg) {
    this.tg = tg;
  }

  public Risk getRisk() {
    return risk;
  }

  public void setRisk(Risk risk) {
    this.risk = risk;
  }

  public List<String> appRedirectAllowPrefixes() {
    return splitCsv(appRedirectAllowlist);
  }

  public static List<String> splitCsv(String csv) {
    if (csv == null || csv.isBlank()) return List.of();
    return Arrays.stream(csv.split(","))
        .map(String::trim)
        .filter(v -> !v.isEmpty())
        .collect(Collectors.toList());
  }

  public static class Web3Auth {
    private String clientId = "";
    private String clientSecret = "";
    private String issuer = "https://auth.status-mvp.local";
    private String audience = "status-mvp";
    private String keyId = "status-mvp-auth-v1";
    private String privateKeyPem = "";

    public String getClientId() {
      return clientId;
    }

    public void setClientId(String clientId) {
      this.clientId = clientId;
    }

    public String getClientSecret() {
      return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
      this.clientSecret = clientSecret;
    }

    public String getIssuer() {
      return issuer;
    }

    public void setIssuer(String issuer) {
      this.issuer = issuer;
    }

    public String getAudience() {
      return audience;
    }

    public void setAudience(String audience) {
      this.audience = audience;
    }

    public String getKeyId() {
      return keyId;
    }

    public void setKeyId(String keyId) {
      this.keyId = keyId;
    }

    public String getPrivateKeyPem() {
      return privateKeyPem;
    }

    public void setPrivateKeyPem(String privateKeyPem) {
      this.privateKeyPem = privateKeyPem;
    }
  }

  public static class AppJwt {
    private String issuer = "status-mvp-price-backend";
    private String audience = "status-mvp-app";
    private String secret = "replace-me-dev-secret-at-least-32-bytes";

    public String getIssuer() {
      return issuer;
    }

    public void setIssuer(String issuer) {
      this.issuer = issuer;
    }

    public String getAudience() {
      return audience;
    }

    public void setAudience(String audience) {
      this.audience = audience;
    }

    public String getSecret() {
      return secret;
    }

    public void setSecret(String secret) {
      this.secret = secret;
    }
  }

  public static class X {
    private String clientId = "";
    private String clientSecret = "";
    private String redirectUri = "";
    private String scopes = "tweet.read users.read offline.access";
    private String authorizeEndpoint = "https://twitter.com/i/oauth2/authorize";
    private String tokenEndpoint = "https://api.twitter.com/2/oauth2/token";
    private String userinfoEndpoint = "https://api.twitter.com/2/users/me";

    public String getClientId() {
      return clientId;
    }

    public void setClientId(String clientId) {
      this.clientId = clientId;
    }

    public String getClientSecret() {
      return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
      this.clientSecret = clientSecret;
    }

    public String getRedirectUri() {
      return redirectUri;
    }

    public void setRedirectUri(String redirectUri) {
      this.redirectUri = redirectUri;
    }

    public String getScopes() {
      return scopes;
    }

    public void setScopes(String scopes) {
      this.scopes = scopes;
    }

    public String getAuthorizeEndpoint() {
      return authorizeEndpoint;
    }

    public void setAuthorizeEndpoint(String authorizeEndpoint) {
      this.authorizeEndpoint = authorizeEndpoint;
    }

    public String getTokenEndpoint() {
      return tokenEndpoint;
    }

    public void setTokenEndpoint(String tokenEndpoint) {
      this.tokenEndpoint = tokenEndpoint;
    }

    public String getUserinfoEndpoint() {
      return userinfoEndpoint;
    }

    public void setUserinfoEndpoint(String userinfoEndpoint) {
      this.userinfoEndpoint = userinfoEndpoint;
    }

    public List<String> scopeList() {
      return AuthProperties.splitCsv(scopes.replace(" ", ","));
    }
  }

  public static class Tg {
    private String botToken = "";
    private long authMaxAgeSeconds = 600;

    public String getBotToken() {
      return botToken;
    }

    public void setBotToken(String botToken) {
      this.botToken = botToken;
    }

    public long getAuthMaxAgeSeconds() {
      return authMaxAgeSeconds;
    }

    public void setAuthMaxAgeSeconds(long authMaxAgeSeconds) {
      this.authMaxAgeSeconds = authMaxAgeSeconds;
    }
  }

  public static class Risk {
    private String blacklistIps = "";
    private String blacklistProviderSubs = "";
    private int loginIpLimit = 20;
    private int loginDeviceLimit = 30;
    private int bindAccountLimit = 20;
    private int windowSeconds = 60;

    public String getBlacklistIps() {
      return blacklistIps;
    }

    public void setBlacklistIps(String blacklistIps) {
      this.blacklistIps = blacklistIps;
    }

    public String getBlacklistProviderSubs() {
      return blacklistProviderSubs;
    }

    public void setBlacklistProviderSubs(String blacklistProviderSubs) {
      this.blacklistProviderSubs = blacklistProviderSubs;
    }

    public int getLoginIpLimit() {
      return loginIpLimit;
    }

    public void setLoginIpLimit(int loginIpLimit) {
      this.loginIpLimit = loginIpLimit;
    }

    public int getLoginDeviceLimit() {
      return loginDeviceLimit;
    }

    public void setLoginDeviceLimit(int loginDeviceLimit) {
      this.loginDeviceLimit = loginDeviceLimit;
    }

    public int getBindAccountLimit() {
      return bindAccountLimit;
    }

    public void setBindAccountLimit(int bindAccountLimit) {
      this.bindAccountLimit = bindAccountLimit;
    }

    public int getWindowSeconds() {
      return windowSeconds;
    }

    public void setWindowSeconds(int windowSeconds) {
      this.windowSeconds = windowSeconds;
    }

    public List<String> blacklistIpList() {
      return AuthProperties.splitCsv(blacklistIps);
    }

    public List<String> blacklistProviderSubList() {
      return AuthProperties.splitCsv(blacklistProviderSubs);
    }
  }
}
