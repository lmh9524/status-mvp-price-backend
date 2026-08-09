package io.statusmvp.pricebackend.controller;

import io.statusmvp.pricebackend.model.wallethistory.WalletHistoryDtos;
import io.statusmvp.pricebackend.service.WalletHistoryService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class WalletHistoryController {
  private final WalletHistoryService walletHistoryService;

  public WalletHistoryController(WalletHistoryService walletHistoryService) {
    this.walletHistoryService = walletHistoryService;
  }

  @PostMapping(
      path = "/wallet-history/query",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public Mono<WalletHistoryDtos.QueryResponse> query(
      @RequestBody(required = false) WalletHistoryDtos.QueryRequest request) {
    return Mono.fromCallable(() -> walletHistoryService.query(request))
        .subscribeOn(Schedulers.boundedElastic());
  }
}
