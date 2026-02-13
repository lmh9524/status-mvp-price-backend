package io.statusmvp.pricebackend.controller;

import io.statusmvp.pricebackend.model.bridge.BridgeAcrossDirectoryResponse;
import io.statusmvp.pricebackend.service.AcrossBridgeDirectoryService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping(path = "/api/v1/bridge/across", produces = MediaType.APPLICATION_JSON_VALUE)
public class BridgeAcrossController {
  private final AcrossBridgeDirectoryService across;

  public BridgeAcrossController(AcrossBridgeDirectoryService across) {
    this.across = across;
  }

  /**
   * Aggregated directory for Across integration:
   * - supported chains + tokens (from /chains)
   * - available routes (from /available-routes)
   * filtered by server-side allowlist and cached in Redis.
   */
  @GetMapping("/directory")
  public Mono<BridgeAcrossDirectoryResponse> directory() {
    return Mono.fromCallable(across::getDirectory).subscribeOn(Schedulers.boundedElastic());
  }
}

