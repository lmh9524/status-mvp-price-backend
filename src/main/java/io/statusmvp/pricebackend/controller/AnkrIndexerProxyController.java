package io.statusmvp.pricebackend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import io.statusmvp.pricebackend.service.AnkrIndexerProxyService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping(path = "/api/v1/indexer", produces = MediaType.APPLICATION_JSON_VALUE)
public class AnkrIndexerProxyController {
  private final AnkrIndexerProxyService proxyService;

  public AnkrIndexerProxyController(AnkrIndexerProxyService proxyService) {
    this.proxyService = proxyService;
  }

  @PostMapping("/ankr")
  public Mono<ResponseEntity<String>> proxyAnkr(@RequestBody(required = false) JsonNode body) {
    return proxyService.proxy(body);
  }
}
