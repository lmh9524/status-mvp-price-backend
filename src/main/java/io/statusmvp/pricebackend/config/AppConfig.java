package io.statusmvp.pricebackend.config;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class AppConfig {

  @Bean
  public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory cf) {
    return new StringRedisTemplate(cf);
  }

  @Bean
  public WebClient webClient() {
    // Bump in-memory buffer slightly for safety (still bounded).
    ExchangeStrategies strategies =
        ExchangeStrategies.builder()
            .codecs(
                c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
            .build();

    return WebClient.builder()
        .exchangeStrategies(strategies)
        .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
        .build();
  }

  @Bean
  public CorsWebFilter corsWebFilter(@Value("${app.cors.allowedOrigins:*}") String allowedOrigins) {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowCredentials(false);
    if (allowedOrigins == null || allowedOrigins.isBlank() || "*".equals(allowedOrigins.trim())) {
      config.addAllowedOriginPattern("*");
    } else {
      List<String> origins =
          Arrays.stream(allowedOrigins.split(","))
              .map(String::trim)
              .filter(s -> !s.isEmpty())
              .toList();
      origins.forEach(config::addAllowedOrigin);
    }
    config.addAllowedHeader("*");
    config.addAllowedMethod("*");
    config.setMaxAge(Duration.ofHours(1));

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return new CorsWebFilter(source);
  }
}


