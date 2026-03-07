package io.statusmvp.pricebackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PriceBackendApplication {
  public static void main(String[] args) {
    SpringApplication.run(PriceBackendApplication.class, args);
  }
}


