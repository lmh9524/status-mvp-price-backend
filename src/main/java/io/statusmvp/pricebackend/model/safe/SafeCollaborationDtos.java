package io.statusmvp.pricebackend.model.safe;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

public final class SafeCollaborationDtos {
  private SafeCollaborationDtos() {}

  public record DiscoveryRequest(@NotEmpty List<@NotNull String> ownerAddresses) {}

  public record DiscoveryItem(int chainId, String safeAddress, List<String> ownerAddresses) {}

  public record DiscoveryResponse(
      List<String> ownerAddresses,
      String syncedAt,
      List<DiscoveryItem> items,
      Map<String, List<Integer>> failedByOwner) {}

  public record InboxRequest(
      @NotEmpty List<@NotNull String> ownerAddresses,
      @Min(1) @Max(100) Integer limit) {}

  public record InboxItem(
      String id,
      int chainId,
      String safeAddress,
      String safeTxHash,
      Integer nonce,
      String to,
      String value,
      String submissionDate,
      String lastActivityAt,
      int confirmationsSubmitted,
      int confirmationsRequired,
      String action,
      List<String> ownerAddresses,
      List<String> actionableOwnerAddresses) {}

  public record InboxResponse(
      List<String> ownerAddresses,
      String syncedAt,
      boolean truncated,
      List<InboxItem> items,
      List<String> failedSafeIds,
      Map<String, List<Integer>> failedByOwner) {}
}
