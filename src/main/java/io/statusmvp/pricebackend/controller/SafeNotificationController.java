package io.statusmvp.pricebackend.controller;

import io.statusmvp.pricebackend.model.safe.SafeNotificationDtos;
import io.statusmvp.pricebackend.service.SafeNotificationService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping(path = "/api/v1/safe/notifications/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
public class SafeNotificationController {
  private final SafeNotificationService notificationService;

  public SafeNotificationController(SafeNotificationService notificationService) {
    this.notificationService = notificationService;
  }

  @PostMapping("/register")
  public Mono<SafeNotificationDtos.RegisterResponse> register(
      @Valid @RequestBody SafeNotificationDtos.RegisterRequest request,
      @RequestHeader(value = "X-Device-Id", required = false) String fallbackDeviceUuid) {
    return notificationService.register(request, fallbackDeviceUuid);
  }

  @PostMapping("/pull")
  public Mono<SafeNotificationDtos.PullResponse> pull(
      @Valid @RequestBody(required = false) SafeNotificationDtos.PullRequest request,
      @RequestHeader(value = "X-Device-Id", required = false) String fallbackDeviceUuid) {
    return notificationService.pull(request, fallbackDeviceUuid);
  }

  @PostMapping("/subscriptions/delete-all")
  public Mono<SafeNotificationDtos.ClearSubscriptionsResponse> clearSubscriptions(
      @RequestHeader(value = "X-Device-Id", required = false) String fallbackDeviceUuid) {
    return notificationService.clearSubscriptions(fallbackDeviceUuid);
  }

  @DeleteMapping("/devices/{deviceUuid}")
  public Mono<SafeNotificationDtos.DeleteDeviceResponse> deleteDevice(
      @PathVariable String deviceUuid) {
    return notificationService.deleteDevice(deviceUuid);
  }
}
