package io.statusmvp.pricebackend.controller;

import io.statusmvp.pricebackend.model.PriceQuote;
import io.statusmvp.pricebackend.service.PriceAggregatorService;
import jakarta.validation.constraints.NotBlank;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
public class PriceController {
  private final PriceAggregatorService prices;

  public PriceController(PriceAggregatorService prices) {
    this.prices = prices;
  }

  @GetMapping("/prices")
  public Mono<List<PriceQuote>> getPrices(
      @RequestParam("symbols") @NotBlank String symbols,
      @RequestParam(value = "currency", required = false, defaultValue = "usd") String currency) {
    List<String> list =
        Arrays.stream(symbols.split(","))
            .map(String::trim)
            .filter(s -> !s.isBlank())
            .toList();
    return Mono.fromCallable(() -> prices.getPrices(list, currency))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @GetMapping("/prices/by-contract")
  public Mono<List<PriceQuote>> getPricesByContract(
      @RequestParam("chainId") int chainId,
      @RequestParam("contractAddresses") @NotBlank String contractAddresses,
      @RequestParam(value = "currency", required = false, defaultValue = "usd") String currency) {
    List<String> list =
        Arrays.stream(contractAddresses.split(","))
            .map(String::trim)
            .filter(s -> !s.isBlank())
            .toList();
    return Mono.fromCallable(() -> prices.getPricesByContract(chainId, list, currency))
        .subscribeOn(Schedulers.boundedElastic());
  }
}


