package io.statusmvp.pricebackend.controller;

import io.statusmvp.pricebackend.model.PortfolioSnapshot;
import io.statusmvp.pricebackend.model.PortfolioSnapshotV2;
import io.statusmvp.pricebackend.service.PortfolioAggregatorService;
import jakarta.validation.constraints.NotBlank;
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
public class PortfolioController {
  private final PortfolioAggregatorService portfolio;

  public PortfolioController(PortfolioAggregatorService portfolio) {
    this.portfolio = portfolio;
  }

  @GetMapping("/portfolio")
  public Mono<PortfolioSnapshot> getPortfolio(
      @RequestParam("address") @NotBlank String address,
      @RequestParam(value = "chainIds", required = false) String chainIds) {
    List<Integer> parsed = portfolio.parseChainIds(chainIds);
    return Mono.fromCallable(() -> portfolio.getPortfolio(address, parsed))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @GetMapping("/portfolio/snapshot")
  public Mono<PortfolioSnapshotV2> getPortfolioSnapshotV2(
      @RequestParam("address") @NotBlank String address,
      @RequestParam(value = "chainIds", required = false) String chainIds,
      @RequestParam(value = "currency", required = false, defaultValue = "usd") String currency,
      @RequestParam(value = "minUsd", required = false) Double minUsd,
      @RequestParam(value = "includeZero", required = false) Boolean includeZero,
      @RequestParam(value = "limit", required = false) Integer limit) {
    List<Integer> parsed = portfolio.parseChainIds(chainIds);
    return Mono.fromCallable(
            () -> portfolio.getPortfolioSnapshotV2(address, parsed, currency, minUsd, includeZero, limit))
        .subscribeOn(Schedulers.boundedElastic());
  }
}
