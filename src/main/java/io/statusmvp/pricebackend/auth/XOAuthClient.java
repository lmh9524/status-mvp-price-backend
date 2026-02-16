package io.statusmvp.pricebackend.auth;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class XOAuthClient {
  private final WebClient webClient;
  private final AuthProperties authProperties;
  private final AuthMetrics metrics;

  public XOAuthClient(WebClient webClient, AuthProperties authProperties, AuthMetrics metrics) {
    this.webClient = webClient;
    this.authProperties = authProperties;
    this.metrics = metrics;
  }

  public String buildAuthorizeUrl(String state, String codeChallenge) {
    AuthProperties.X x = authProperties.getX();
    String scope = String.join(" ", x.scopeList());
    return UriComponentsBuilder.fromUriString(x.getAuthorizeEndpoint())
        .queryParam("response_type", "code")
        .queryParam("client_id", x.getClientId())
        .queryParam("redirect_uri", x.getRedirectUri())
        .queryParam("scope", scope)
        .queryParam("state", state)
        .queryParam("code_challenge", codeChallenge)
        .queryParam("code_challenge_method", "S256")
        .build()
        .encode(StandardCharsets.UTF_8)
        .toUriString();
  }

  public String exchangeCodeForAccessToken(String code, String codeVerifier) {
    AuthProperties.X x = authProperties.getX();
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "authorization_code");
    form.add("client_id", x.getClientId());
    form.add("redirect_uri", x.getRedirectUri());
    form.add("code_verifier", codeVerifier);
    form.add("code", code);

    String clientSecret = x.getClientSecret() == null ? "" : x.getClientSecret();
    String basic = "";
    if (!clientSecret.isBlank()) {
      basic =
          Base64.getEncoder()
              .encodeToString((x.getClientId() + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
    }

    JsonNode response;
    try {
      WebClient.RequestBodySpec req =
          webClient
              .post()
              .uri(x.getTokenEndpoint())
              .contentType(MediaType.APPLICATION_FORM_URLENCODED);
      if (!basic.isBlank()) {
        req = req.header(HttpHeaders.AUTHORIZATION, "Basic " + basic);
      }
      response =
          req.body(BodyInserters.fromFormData(form))
              .retrieve()
              .bodyToMono(JsonNode.class)
              .timeout(Duration.ofSeconds(10))
              .block();
    } catch (Exception e) {
      metrics.providerUnavailable("x");
      throw new AuthException(AuthErrorCode.OAUTH_EXCHANGE_FAILED, "x oauth token exchange failed", 502);
    }
    if (response == null) {
      metrics.providerUnavailable("x");
      throw new AuthException(AuthErrorCode.OAUTH_EXCHANGE_FAILED, "x oauth empty token response", 502);
    }
    String accessToken = response.path("access_token").asText("");
    if (accessToken.isBlank()) {
      metrics.providerUnavailable("x");
      throw new AuthException(AuthErrorCode.OAUTH_EXCHANGE_FAILED, "x oauth missing access token", 502);
    }
    return accessToken;
  }

  public String fetchUserId(String accessToken) {
    AuthProperties.X x = authProperties.getX();
    JsonNode response;
    try {
      response =
          webClient
              .get()
              .uri(x.getUserinfoEndpoint())
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
              .retrieve()
              .bodyToMono(JsonNode.class)
              .timeout(Duration.ofSeconds(10))
              .block();
    } catch (Exception e) {
      metrics.providerUnavailable("x");
      throw new AuthException(AuthErrorCode.PROVIDER_UNAVAILABLE, "x provider unavailable", 503);
    }
    String userId = response == null ? "" : response.path("data").path("id").asText("");
    if (userId.isBlank()) {
      metrics.providerUnavailable("x");
      throw new AuthException(AuthErrorCode.PROVIDER_UNAVAILABLE, "x user id not found", 503);
    }
    return userId;
  }
}
