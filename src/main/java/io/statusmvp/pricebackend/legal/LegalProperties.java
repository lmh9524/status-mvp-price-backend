package io.statusmvp.pricebackend.legal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.legal")
public class LegalProperties {
  private String termsUrl = "";
  private String privacyUrl = "";

  public String getTermsUrl() {
    return termsUrl;
  }

  public void setTermsUrl(String termsUrl) {
    this.termsUrl = termsUrl;
  }

  public String getPrivacyUrl() {
    return privacyUrl;
  }

  public void setPrivacyUrl(String privacyUrl) {
    this.privacyUrl = privacyUrl;
  }
}
