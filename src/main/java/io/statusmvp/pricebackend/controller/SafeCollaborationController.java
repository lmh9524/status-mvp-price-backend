package io.statusmvp.pricebackend.controller;

import io.statusmvp.pricebackend.model.safe.SafeCollaborationDtos;
import io.statusmvp.pricebackend.service.SafeCollaborationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping(path = "/api/v1/safe/collaboration", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
public class SafeCollaborationController {
  private final SafeCollaborationService collaborationService;

  public SafeCollaborationController(SafeCollaborationService collaborationService) {
    this.collaborationService = collaborationService;
  }

  @PostMapping("/discovery/query")
  public Mono<SafeCollaborationDtos.DiscoveryResponse> queryDiscovery(
      @Valid @RequestBody SafeCollaborationDtos.DiscoveryRequest request,
      @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
      ServerWebExchange exchange) {
    return collaborationService.queryDiscoveredSafes(
        request, resolveClientIp(exchange), requireDeviceId(deviceId));
  }

  @PostMapping("/inbox/query")
  public Mono<SafeCollaborationDtos.InboxResponse> queryInbox(
      @Valid @RequestBody SafeCollaborationDtos.InboxRequest request,
      @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
      ServerWebExchange exchange) {
    return collaborationService.queryInbox(
        request, resolveClientIp(exchange), requireDeviceId(deviceId));
  }

  private static String requireDeviceId(String deviceId) {
    if (deviceId == null || deviceId.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing X-Device-Id");
    }
    return deviceId.trim();
  }

  private static String resolveClientIp(ServerWebExchange exchange) {
    String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      int idx = forwarded.indexOf(',');
      return idx > 0 ? forwarded.substring(0, idx).trim() : forwarded.trim();
    }
    String realIp = exchange.getRequest().getHeaders().getFirst("X-Real-IP");
    if (realIp != null && !realIp.isBlank()) {
      return realIp.trim();
    }
    if (exchange.getRequest().getRemoteAddress() == null
        || exchange.getRequest().getRemoteAddress().getAddress() == null) {
      return "";
    }
    return exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
  }
}
