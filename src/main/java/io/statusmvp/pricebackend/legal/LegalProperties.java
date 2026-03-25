package io.statusmvp.pricebackend.legal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.legal")
public class LegalProperties {
  private String termsUrl = "";
  private String privacyUrl = "";
  private String appName = "Veil Wallet";
  private String entityName = "VeilLabs";
  private String contactEmail = "veillabs.wallet@gmail.com";
  private String contactAddress = "香港九龙区";
  private String effectiveDate = "2026-02-20";
  private String governingLaw = "Laws of the Hong Kong Special Administrative Region";
  private String governingLawZh = "香港特别行政区法律";

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

  public String getAppName() {
    return appName;
  }

  public void setAppName(String appName) {
    this.appName = appName;
  }

  public String getEntityName() {
    return entityName;
  }

  public void setEntityName(String entityName) {
    this.entityName = entityName;
  }

  public String getContactEmail() {
    return contactEmail;
  }

  public void setContactEmail(String contactEmail) {
    this.contactEmail = contactEmail;
  }

  public String getContactAddress() {
    return contactAddress;
  }

  public void setContactAddress(String contactAddress) {
    this.contactAddress = contactAddress;
  }

  public String getEffectiveDate() {
    return effectiveDate;
  }

  public void setEffectiveDate(String effectiveDate) {
    this.effectiveDate = effectiveDate;
  }

  public String getGoverningLaw() {
    return governingLaw;
  }

  public void setGoverningLaw(String governingLaw) {
    this.governingLaw = governingLaw;
  }

  public String getGoverningLawZh() {
    return governingLawZh;
  }

  public void setGoverningLawZh(String governingLawZh) {
    this.governingLawZh = governingLawZh;
  }
}
