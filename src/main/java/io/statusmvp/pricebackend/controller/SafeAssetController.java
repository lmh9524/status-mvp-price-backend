package io.statusmvp.pricebackend.controller;

import io.statusmvp.pricebackend.model.safe.SafeAssetBalanceItem;
import io.statusmvp.pricebackend.service.SafeAssetBalanceService;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping(path = "/api/v1/safe", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
public class SafeAssetController {
  private final SafeAssetBalanceService safeAssetBalances;

  public SafeAssetController(SafeAssetBalanceService safeAssetBalances) {
    this.safeAssetBalances = safeAssetBalances;
  }

  @GetMapping("/assets")
  public Mono<List<SafeAssetBalanceItem>> listSafeAssets(
      @RequestParam("chainId") int chainId,
      @RequestParam("safeAddress") @NotBlank String safeAddress,
      @RequestParam(value = "trusted", required = false, defaultValue = "false") boolean trusted) {
    return safeAssetBalances.listBalances(chainId, safeAddress, trusted);
  }
}
