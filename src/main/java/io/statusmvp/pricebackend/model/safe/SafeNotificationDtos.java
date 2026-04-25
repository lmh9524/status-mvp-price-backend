package io.statusmvp.pricebackend.model.safe;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;

public final class SafeNotificationDtos {
  private SafeNotificationDtos() {}

  public record SafeSubscription(
      @Min(1) int chainId,
      @NotBlank String address,
      List<String> ownerAddresses,
      List<String> notificationTypes) {}

  public record RegisterRequest(
      String deviceUuid,
      @Pattern(regexp = "^(ios|android)$") String deviceType,
      String cloudMessagingToken,
      @NotNull List<@Valid SafeSubscription> safes) {}

  public record RegisterResponse(
      String deviceUuid, String transport, String registeredAt, int subscriptionCount) {}

  public record PullRequest(String deviceUuid, @Min(1) @Max(100) Integer limit) {}

  public record NotificationItem(
      String id,
      String notificationType,
      int chainId,
      String safeAddress,
      String safeTxHash,
      int confirmationsSubmitted,
      int confirmationsRequired,
      String transactionHash,
      String eventState,
      String createdAt) {}

  public record PullResponse(
      String deviceUuid, String transport, String pulledAt, List<NotificationItem> items) {}

  public record ClearSubscriptionsResponse(String deviceUuid, int removedCount) {}

  public record DeleteDeviceResponse(String deviceUuid, boolean deleted) {}
}
