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

@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
public class PriceController {
  private final PriceAggregatorService prices;

  public PriceController(PriceAggregatorService prices) {
    this.prices = prices;
  }

  @GetMapping("/prices")
  public List<PriceQuote> getPrices(
      @RequestParam("symbols") @NotBlank String symbols,
      @RequestParam(value = "currency", required = false, defaultValue = "usd") String currency) {
    List<String> list =
        Arrays.stream(symbols.split(","))
            .map(String::trim)
            .filter(s -> !s.isBlank())
            .toList();
    return prices.getPrices(list, currency);
  }

  @GetMapping("/prices/by-contract")
  public List<PriceQuote> getPricesByContract(
      @RequestParam("chainId") int chainId,
      @RequestParam("contractAddresses") @NotBlank String contractAddresses,
      @RequestParam(value = "currency", required = false, defaultValue = "usd") String currency) {
    List<String> list =
        Arrays.stream(contractAddresses.split(","))
            .map(String::trim)
            .filter(s -> !s.isBlank())
            .toList();
    return prices.getPricesByContract(chainId, list, currency);
  }
}


