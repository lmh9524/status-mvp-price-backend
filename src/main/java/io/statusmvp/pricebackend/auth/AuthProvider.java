package io.statusmvp.pricebackend.auth;

public enum AuthProvider {
  X("x"),
  TG("tg");

  private final String code;

  AuthProvider(String code) {
    this.code = code;
  }

  public String code() {
    return code;
  }

  public static AuthProvider fromCode(String value) {
    if (value == null) throw new IllegalArgumentException("provider required");
    for (AuthProvider provider : values()) {
      if (provider.code.equalsIgnoreCase(value.trim())) return provider;
    }
    throw new IllegalArgumentException("unsupported provider: " + value);
  }
}

