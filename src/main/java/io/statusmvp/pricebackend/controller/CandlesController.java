package io.statusmvp.pricebackend.controller;

import io.statusmvp.pricebackend.model.CandleResponse;
import io.statusmvp.pricebackend.service.CandleAggregatorService;
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
public class CandlesController {
  private final CandleAggregatorService candles;

  public CandlesController(CandleAggregatorService candles) {
    this.candles = candles;
  }

  @GetMapping("/candles")
  public Mono<CandleResponse> getCandles(
      @RequestParam(value = "chainId", required = false) Integer chainId,
      @RequestParam(value = "contractAddress", required = false) String contractAddress,
      @RequestParam(value = "symbol", required = false) String symbol,
      @RequestParam(value = "interval", required = false, defaultValue = "1h") String interval,
      @RequestParam(value = "limit", required = false, defaultValue = "160") int limit) {
    return Mono.fromCallable(
            () -> candles.getCandles(chainId, contractAddress, symbol, interval, limit))
        .subscribeOn(Schedulers.boundedElastic());
  }
}
