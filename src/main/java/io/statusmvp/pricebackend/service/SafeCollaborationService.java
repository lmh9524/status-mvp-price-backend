package io.statusmvp.pricebackend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.statusmvp.pricebackend.model.safe.SafeCollaborationDtos;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.web3j.crypto.Keys;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class SafeCollaborationService {
  private static final Logger log = LoggerFactory.getLogger(SafeCollaborationService.class);

  private static final SafeTxServiceGatewayService.CachePolicy OWNER_DISCOVERY_CACHE =
      new SafeTxServiceGatewayService.CachePolicy(60, 300, 30);
  private static final SafeTxServiceGatewayService.CachePolicy QUEUE_CACHE =
      new SafeTxServiceGatewayService.CachePolicy(45, 120, 5);
  private static final SafeTxServiceGatewayService.CachePolicy TX_DETAIL_CACHE =
      new SafeTxServiceGatewayService.CachePolicy(45, 120, 5);

  private static final int OWNER_PAGE_LIMIT = 100;
  private static final int OWNER_MAX_PAGES = 5;
  private static final int DEFAULT_INBOX_LIMIT = 50;
  private static final int MAX_INBOX_LIMIT = 100;
  private static final int SAFE_QUEUE_PAGE_LIMIT = 10;
  private static final int SAFE_QUEUE_MAX_PAGES = 5;
  private static final int SAFE_QUEUE_MAX_ITEMS = 20;
  private static final int OWNER_CONCURRENCY = 2;
  private static final int OWNER_CHAIN_CONCURRENCY = 1;
  private static final int SAFE_CONCURRENCY = 2;
  private static final int TX_DETAIL_CONCURRENCY = 2;

  private static final Pattern EVM_ADDRESS_PATTERN = Pattern.compile("^0x[0-9a-fA-F]{40}$");

  private static final List<SafeChain> SUPPORTED_CHAINS =
      List.of(
          new SafeChain(1, "eth"),
          new SafeChain(10, "oeth"),
          new SafeChain(56, "bnb"),
          new SafeChain(8453, "base"),
          new SafeChain(42161, "arb1"));

  private final SafeTxServiceGatewayService gateway;
  private final ObjectMapper objectMapper;

  public SafeCollaborationService(
      SafeTxServiceGatewayService gateway, ObjectMapper objectMapper) {
    this.gateway = gateway;
    this.objectMapper = objectMapper;
  }

  public Mono<SafeCollaborationDtos.DiscoveryResponse> queryDiscoveredSafes(
      SafeCollaborationDtos.DiscoveryRequest request, String clientIp, String deviceId) {
    List<String> ownerAddresses = normalizeOwnerAddresses(request.ownerAddresses());
    if (ownerAddresses.isEmpty()) {
      return Mono.just(
          new SafeCollaborationDtos.DiscoveryResponse(
              List.of(), Instant.now().toString(), List.of(), Map.of()));
    }

    return fetchDiscoverySnapshot(ownerAddresses, clientIp, deviceId)
        .map(
            snapshot ->
                new SafeCollaborationDtos.DiscoveryResponse(
                    ownerAddresses,
                    Instant.now().toString(),
                    snapshot.items(),
                    snapshot.failedByOwner()));
  }

  public Mono<SafeCollaborationDtos.InboxResponse> queryInbox(
      SafeCollaborationDtos.InboxRequest request, String clientIp, String deviceId) {
    List<String> ownerAddresses = normalizeOwnerAddresses(request.ownerAddresses());
    if (ownerAddresses.isEmpty()) {
      return Mono.just(
          new SafeCollaborationDtos.InboxResponse(
              List.of(), Instant.now().toString(), false, List.of(), List.of(), Map.of()));
    }

    int limit = sanitizeLimit(request.limit());

    return fetchDiscoverySnapshot(ownerAddresses, clientIp, deviceId)
        .flatMap(snapshot -> buildInboxResponse(snapshot, ownerAddresses, limit, clientIp, deviceId));
  }

  public Mono<List<SafeCollaborationDtos.InboxItem>> queryInboxItemsForSafes(
      List<SafeCollaborationDtos.DiscoveryItem> items, int limit, String clientIp, String deviceId) {
    List<SafeCollaborationDtos.DiscoveryItem> normalizedItems = normalizeDiscoveryItems(items);
    if (normalizedItems.isEmpty()) {
      return Mono.just(List.of());
    }

    return buildInboxResponse(
            new DiscoverySnapshot(normalizedItems, Map.of()),
            List.of(),
            sanitizeLimit(limit),
            clientIp,
            deviceId)
        .map(SafeCollaborationDtos.InboxResponse::items);
  }

  private Mono<DiscoverySnapshot> fetchDiscoverySnapshot(
      List<String> ownerAddresses, String clientIp, String deviceId) {
    return Flux.fromIterable(ownerAddresses)
        .flatMap(
            ownerAddress ->
                Flux.fromIterable(SUPPORTED_CHAINS)
                    .flatMap(
                        chain ->
                            fetchSafesByOwner(chain, ownerAddress, clientIp, deviceId)
                                .map(
                                    safes ->
                                        new OwnerChainDiscoveryResult(
                                            ownerAddress, chain.chainId(), safes, false))
                                .onErrorResume(
                                    error -> {
                                      log.warn(
                                          "safe.discovery.partial_failure owner={} chainId={} error={}",
                                          ownerAddress,
                                          chain.chainId(),
                                          error.getMessage());
                                      return Mono.just(
                                          new OwnerChainDiscoveryResult(
                                              ownerAddress, chain.chainId(), List.of(), true));
                                    }),
                        OWNER_CHAIN_CONCURRENCY),
            OWNER_CONCURRENCY)
        .collectList()
        .map(this::mergeDiscoveryResults);
  }

  private DiscoverySnapshot mergeDiscoveryResults(List<OwnerChainDiscoveryResult> results) {
    Map<String, DiscoveryAccumulator> itemsBySafeKey = new LinkedHashMap<>();
    Map<String, LinkedHashSet<Integer>> failedByOwner = new LinkedHashMap<>();

    for (OwnerChainDiscoveryResult result : results) {
      if (result.failed()) {
        failedByOwner
            .computeIfAbsent(result.ownerAddress(), ignored -> new LinkedHashSet<>())
            .add(result.chainId());
        continue;
      }
      for (String safeAddress : result.safeAddresses()) {
        String key = safeKey(result.chainId(), safeAddress);
        itemsBySafeKey
            .computeIfAbsent(key, ignored -> new DiscoveryAccumulator(result.chainId(), safeAddress))
            .ownerAddresses()
            .add(result.ownerAddress());
      }
    }

    List<SafeCollaborationDtos.DiscoveryItem> items =
        itemsBySafeKey.values().stream()
            .map(
                accumulator ->
                    new SafeCollaborationDtos.DiscoveryItem(
                        accumulator.chainId(),
                        accumulator.safeAddress(),
                        List.copyOf(accumulator.ownerAddresses())))
            .sorted(
                Comparator.comparingInt(SafeCollaborationDtos.DiscoveryItem::chainId)
                    .thenComparing(item -> item.safeAddress().toLowerCase(Locale.ROOT)))
            .toList();

    Map<String, List<Integer>> failed =
        failedByOwner.entrySet().stream()
            .collect(
                LinkedHashMap::new,
                (map, entry) -> map.put(entry.getKey(), List.copyOf(entry.getValue())),
                LinkedHashMap::putAll);

    return new DiscoverySnapshot(items, failed);
  }

  private Mono<List<String>> fetchSafesByOwner(
      SafeChain chain, String ownerAddress, String clientIp, String deviceId) {
    return fetchSafesByOwnerPage(
        chain, ownerAddress, 0, OWNER_MAX_PAGES, new LinkedHashSet<>(), clientIp, deviceId);
  }

  private Mono<List<String>> fetchSafesByOwnerPage(
      SafeChain chain,
      String ownerAddress,
      int offset,
      int remainingPages,
      LinkedHashSet<String> accumulator,
      String clientIp,
      String deviceId) {
    MultiValueMap<String, String> query = new LinkedMultiValueMap<>();
    query.add("limit", String.valueOf(OWNER_PAGE_LIMIT));
    query.add("offset", String.valueOf(Math.max(0, offset)));

    return gateway
        .get(
            chain.code(),
            "/api/v2/owners/" + ownerAddress + "/safes/",
            query,
            clientIp,
            deviceId,
            OWNER_DISCOVERY_CACHE)
        .flatMap(
            response -> {
              int status = response.getStatusCode().value();
              if (status == 404) {
                return Mono.just(List.copyOf(accumulator));
              }
              if (status < 200 || status >= 300) {
                return Mono.error(
                    new IllegalStateException(
                        "owner safes upstream returned " + status + " for " + ownerAddress));
              }

              SafesByOwnerPage page = parseSafesByOwnerPage(response);
              accumulator.addAll(page.safeAddresses());

              if (page.nextOffset().isEmpty() || remainingPages <= 1) {
                if (remainingPages <= 1 && page.nextOffset().isPresent()) {
                  log.warn(
                      "safe.discovery.max_pages chainId={} owner={}",
                      chain.chainId(),
                      ownerAddress);
                }
                return Mono.just(List.copyOf(accumulator));
              }

              return fetchSafesByOwnerPage(
                  chain,
                  ownerAddress,
                  page.nextOffset().get(),
                  remainingPages - 1,
                  accumulator,
                  clientIp,
                  deviceId);
            });
  }

  private Mono<SafeCollaborationDtos.InboxResponse> buildInboxResponse(
      DiscoverySnapshot snapshot,
      List<String> ownerAddresses,
      int limit,
      String clientIp,
      String deviceId) {
    if (snapshot.items().isEmpty()) {
      return Mono.just(
          new SafeCollaborationDtos.InboxResponse(
              ownerAddresses,
              Instant.now().toString(),
              false,
              List.of(),
              List.of(),
              snapshot.failedByOwner()));
    }

    int perSafeQueueLimit = Math.min(SAFE_QUEUE_MAX_ITEMS, Math.max(SAFE_QUEUE_PAGE_LIMIT, limit));

    return Flux.fromIterable(snapshot.items())
        .flatMap(
            discoveryItem ->
                fetchInboxItemsForSafe(discoveryItem, perSafeQueueLimit, clientIp, deviceId)
                    .onErrorResume(
                        error -> {
                          String safeId = safeKey(discoveryItem.chainId(), discoveryItem.safeAddress());
                          log.warn("safe.inbox.partial_failure safeId={} error={}", safeId, error.getMessage());
                          return Mono.just(new SafeInboxBatch(List.of(), Set.of(safeId)));
                        }),
            SAFE_CONCURRENCY)
        .collectList()
        .map(
            batches -> {
              List<SafeCollaborationDtos.InboxItem> mergedItems = new ArrayList<>();
              LinkedHashSet<String> failedSafeIds = new LinkedHashSet<>();

              for (SafeInboxBatch batch : batches) {
                mergedItems.addAll(batch.items());
                failedSafeIds.addAll(batch.failedSafeIds());
              }

              mergedItems.sort(
                  Comparator.comparing(
                          (SafeCollaborationDtos.InboxItem item) ->
                              sortTimestamp(item.lastActivityAt(), item.submissionDate()))
                      .reversed()
                      .thenComparing(item -> item.id().toLowerCase(Locale.ROOT)));

              boolean truncated = mergedItems.size() > limit;
              List<SafeCollaborationDtos.InboxItem> finalItems =
                  truncated ? List.copyOf(mergedItems.subList(0, limit)) : List.copyOf(mergedItems);

              return new SafeCollaborationDtos.InboxResponse(
                  ownerAddresses,
                  Instant.now().toString(),
                  truncated,
                  finalItems,
                  List.copyOf(failedSafeIds),
                  snapshot.failedByOwner());
            });
  }

  private Mono<SafeInboxBatch> fetchInboxItemsForSafe(
      SafeCollaborationDtos.DiscoveryItem discoveryItem,
      int queueLimit,
      String clientIp,
      String deviceId) {
    SafeChain chain = chainById(discoveryItem.chainId());
    if (chain == null) {
      return Mono.just(
          new SafeInboxBatch(List.of(), Set.of(safeKey(discoveryItem.chainId(), discoveryItem.safeAddress()))));
    }

    String safeId = safeKey(discoveryItem.chainId(), discoveryItem.safeAddress());

    return fetchPendingQueue(chain, discoveryItem.safeAddress(), queueLimit, clientIp, deviceId)
        .flatMap(
            queueItems -> {
              if (queueItems.isEmpty()) {
                return Mono.just(new SafeInboxBatch(List.of(), Set.of()));
              }

              final boolean[] hadDetailFailure = {false};
              return Flux.fromIterable(queueItems)
                  .flatMap(
                      queueItem ->
                          fetchTransactionDetail(chain, queueItem.safeTxHash(), clientIp, deviceId)
                              .map(detail -> new InboxDetailResult(classifyInboxItem(discoveryItem, detail), false))
                              .onErrorResume(
                                  error -> {
                                    hadDetailFailure[0] = true;
                                    log.warn(
                                        "safe.inbox.detail_failure safeId={} safeTxHash={} error={}",
                                        safeId,
                                        queueItem.safeTxHash(),
                                        error.getMessage());
                                    return Mono.just(new InboxDetailResult(null, true));
                                  }),
                      TX_DETAIL_CONCURRENCY)
                  .collectList()
                  .map(
                      detailResults -> {
                        List<SafeCollaborationDtos.InboxItem> items = new ArrayList<>();
                        for (InboxDetailResult detailResult : detailResults) {
                          if (detailResult.item() != null) {
                            items.add(detailResult.item());
                          }
                        }
                        Set<String> failedSafeIds = hadDetailFailure[0] ? Set.of(safeId) : Set.of();
                        return new SafeInboxBatch(List.copyOf(items), failedSafeIds);
                      });
            })
        .onErrorResume(error -> Mono.just(new SafeInboxBatch(List.of(), Set.of(safeId))));
  }

  private Mono<List<QueueItem>> fetchPendingQueue(
      SafeChain chain,
      String safeAddress,
      int queueLimit,
      String clientIp,
      String deviceId) {
    return fetchPendingQueuePage(
        chain,
        safeAddress,
        0,
        Math.min(Math.max(queueLimit, SAFE_QUEUE_PAGE_LIMIT), SAFE_QUEUE_MAX_ITEMS),
        SAFE_QUEUE_MAX_PAGES,
        new LinkedHashMap<>(),
        clientIp,
        deviceId);
  }

  private Mono<List<QueueItem>> fetchPendingQueuePage(
      SafeChain chain,
      String safeAddress,
      int offset,
      int queueLimit,
      int remainingPages,
      LinkedHashMap<String, QueueItem> accumulator,
      String clientIp,
      String deviceId) {
    MultiValueMap<String, String> query = new LinkedMultiValueMap<>();
    query.add("executed", "false");
    query.add("ordering", "-modified");
    query.add("limit", String.valueOf(SAFE_QUEUE_PAGE_LIMIT));
    query.add("offset", String.valueOf(Math.max(0, offset)));

    return gateway
        .get(
            chain.code(),
            "/api/v2/safes/" + safeAddress + "/multisig-transactions/",
            query,
            clientIp,
            deviceId,
            QUEUE_CACHE)
        .flatMap(
            response -> {
              int status = response.getStatusCode().value();
              if (status == 404) {
                return Mono.just(List.of());
              }
              if (status < 200 || status >= 300) {
                return Mono.error(
                    new IllegalStateException(
                        "safe queue upstream returned " + status + " for " + safeAddress));
              }

              QueuePage page = parsePendingQueue(response);
              for (QueueItem item : page.items()) {
                if (accumulator.size() >= queueLimit) {
                  break;
                }
                accumulator.putIfAbsent(item.safeTxHash().toLowerCase(Locale.ROOT), item);
              }

              if (accumulator.size() >= queueLimit || page.nextOffset().isEmpty() || remainingPages <= 1) {
                if (remainingPages <= 1 && page.nextOffset().isPresent()) {
                  log.warn(
                      "safe.inbox.queue.max_pages chainId={} safe={}",
                      chain.chainId(),
                      safeAddress);
                }
                return Mono.just(List.copyOf(accumulator.values()));
              }

              return fetchPendingQueuePage(
                  chain,
                  safeAddress,
                  page.nextOffset().get(),
                  queueLimit,
                  remainingPages - 1,
                  accumulator,
                  clientIp,
                  deviceId);
            });
  }

  private Mono<TransactionDetail> fetchTransactionDetail(
      SafeChain chain, String safeTxHash, String clientIp, String deviceId) {
    return gateway
        .get(
            chain.code(),
            "/api/v2/multisig-transactions/" + safeTxHash + "/",
            null,
            clientIp,
            deviceId,
            TX_DETAIL_CACHE)
        .flatMap(
            response -> {
              int status = response.getStatusCode().value();
              if (status < 200 || status >= 300) {
                return Mono.error(
                    new IllegalStateException(
                        "tx detail upstream returned " + status + " for " + safeTxHash));
              }
              return Mono.just(parseTransactionDetail(response));
            });
  }

  private SafeCollaborationDtos.InboxItem classifyInboxItem(
      SafeCollaborationDtos.DiscoveryItem discoveryItem, TransactionDetail detail) {
    int confirmationsRequired = Math.max(0, detail.confirmationsRequired());
    int confirmationsSubmitted = detail.confirmations().size();
    if (confirmationsRequired <= 0) {
      return null;
    }

    Set<String> signedOwners = new LinkedHashSet<>();
    for (String owner : detail.confirmations()) {
      String normalized = normalizeAddress(owner);
      if (normalized != null) {
        signedOwners.add(normalized);
      }
    }

    List<String> actionableOwnerAddresses =
        discoveryItem.ownerAddresses().stream()
            .filter(ownerAddress -> !signedOwners.contains(normalizeAddress(ownerAddress)))
            .toList();

    String action;
    List<String> actionOwners;
    if (confirmationsSubmitted >= confirmationsRequired) {
      action = "ready_to_execute";
      actionOwners = List.copyOf(discoveryItem.ownerAddresses());
    } else if (!actionableOwnerAddresses.isEmpty()) {
      action = "needs_confirmation";
      actionOwners = actionableOwnerAddresses;
    } else {
      return null;
    }

    return new SafeCollaborationDtos.InboxItem(
        discoveryItem.chainId() + ":" + detail.safeTxHash().toLowerCase(Locale.ROOT),
        discoveryItem.chainId(),
        discoveryItem.safeAddress(),
        detail.safeTxHash(),
        detail.nonce(),
        detail.to(),
        detail.value(),
        detail.submissionDate(),
        detail.lastActivityAt(),
        confirmationsSubmitted,
        confirmationsRequired,
        action,
        List.copyOf(discoveryItem.ownerAddresses()),
        actionOwners);
  }

  private SafesByOwnerPage parseSafesByOwnerPage(ResponseEntity<String> response) {
    JsonNode root = readTree(response.getBody());
    List<String> safeAddresses = new ArrayList<>();
    for (JsonNode item : root.path("results")) {
      String safeAddress =
          item != null && item.isTextual() ? item.asText() : textOrNull(item == null ? null : item.get("address"));
      String normalized = canonicalAddress(safeAddress);
      if (normalized != null) {
        safeAddresses.add(normalized);
      }
    }
    return new SafesByOwnerPage(safeAddresses, parseOffset(textOrNull(root.get("next"))));
  }

  private QueuePage parsePendingQueue(ResponseEntity<String> response) {
    JsonNode root = readTree(response.getBody());
    List<QueueItem> items = new ArrayList<>();
    for (JsonNode item : root.path("results")) {
      String safeTxHash = textOrNull(item.get("safeTxHash"));
      if (safeTxHash == null || safeTxHash.isBlank()) {
        continue;
      }
      items.add(new QueueItem(safeTxHash));
    }
    return new QueuePage(items, parseOffset(textOrNull(root.get("next"))));
  }

  private TransactionDetail parseTransactionDetail(ResponseEntity<String> response) {
    JsonNode root = readTree(response.getBody());
    String safeTxHash = textOrNull(root.get("safeTxHash"));
    List<String> confirmations = new ArrayList<>();
    for (JsonNode confirmation : root.path("confirmations")) {
      String owner = textOrNull(confirmation.get("owner"));
      String normalized = normalizeAddress(owner);
      if (normalized != null) {
        confirmations.add(normalized);
      }
    }
    return new TransactionDetail(
        safeTxHash == null ? "" : safeTxHash,
        canonicalAddress(textOrNull(root.get("safe"))),
        textOrNull(root.get("to")),
        textOrNull(root.get("value")),
        intOrNull(root.get("nonce")),
        intOrZero(root.get("confirmationsRequired")),
        confirmations,
        textOrNull(root.get("submissionDate")),
        firstNonBlank(textOrNull(root.get("modified")), textOrNull(root.get("submissionDate"))));
  }

  private JsonNode readTree(String body) {
    try {
      return objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
    } catch (Exception error) {
      throw new IllegalStateException("Failed to parse upstream JSON", error);
    }
  }

  private static String textOrNull(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    String value = node.asText(null);
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static Integer intOrNull(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (node.isInt() || node.isLong()) {
      return node.asInt();
    }
    String text = textOrNull(node);
    if (text == null) {
      return null;
    }
    try {
      return Integer.valueOf(text);
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private static int intOrZero(JsonNode node) {
    Integer value = intOrNull(node);
    return value == null ? 0 : value;
  }

  private static int sanitizeLimit(Integer requestedLimit) {
    if (requestedLimit == null) {
      return DEFAULT_INBOX_LIMIT;
    }
    return Math.max(1, Math.min(MAX_INBOX_LIMIT, requestedLimit));
  }

  private static String safeKey(int chainId, String safeAddress) {
    return chainId + ":" + (safeAddress == null ? "" : safeAddress.toLowerCase(Locale.ROOT));
  }

  private static List<String> normalizeOwnerAddresses(List<String> ownerAddresses) {
    if (ownerAddresses == null || ownerAddresses.isEmpty()) {
      return List.of();
    }
    LinkedHashSet<String> normalized = new LinkedHashSet<>();
    for (String ownerAddress : ownerAddresses) {
      String address = canonicalAddress(ownerAddress);
      if (address != null) {
        normalized.add(address);
      }
    }
    return List.copyOf(normalized);
  }

  private static List<SafeCollaborationDtos.DiscoveryItem> normalizeDiscoveryItems(
      List<SafeCollaborationDtos.DiscoveryItem> items) {
    if (items == null || items.isEmpty()) {
      return List.of();
    }

    List<SafeCollaborationDtos.DiscoveryItem> normalized = new ArrayList<>();
    for (SafeCollaborationDtos.DiscoveryItem item : items) {
      if (item == null || item.chainId() <= 0) {
        continue;
      }

      String safeAddress = canonicalAddress(item.safeAddress());
      List<String> ownerAddresses = normalizeOwnerAddresses(item.ownerAddresses());
      if (safeAddress == null || ownerAddresses.isEmpty()) {
        continue;
      }

      normalized.add(
          new SafeCollaborationDtos.DiscoveryItem(item.chainId(), safeAddress, ownerAddresses));
    }
    return List.copyOf(normalized);
  }

  private static String canonicalAddress(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    if (!EVM_ADDRESS_PATTERN.matcher(trimmed).matches()) {
      return null;
    }
    try {
      return Keys.toChecksumAddress(trimmed);
    } catch (Exception ignored) {
      return null;
    }
  }

  private static String normalizeAddress(String value) {
    String canonical = canonicalAddress(value);
    return canonical == null ? null : canonical.toLowerCase(Locale.ROOT);
  }

  private static Optional<Integer> parseOffset(String nextUrl) {
    if (nextUrl == null || nextUrl.isBlank()) {
      return Optional.empty();
    }
    try {
      URI uri = URI.create(nextUrl);
      String query = uri.getQuery();
      if (query == null || query.isBlank()) {
        return Optional.empty();
      }
      for (String pair : query.split("&")) {
        int idx = pair.indexOf('=');
        String key = idx >= 0 ? pair.substring(0, idx) : pair;
        if (!"offset".equals(key)) {
          continue;
        }
        String value = idx >= 0 ? pair.substring(idx + 1) : "";
        return Optional.of(Integer.parseInt(value));
      }
      return Optional.empty();
    } catch (Exception ignored) {
      return Optional.empty();
    }
  }

  private static SafeChain chainById(int chainId) {
    for (SafeChain chain : SUPPORTED_CHAINS) {
      if (chain.chainId() == chainId) {
        return chain;
      }
    }
    return null;
  }

  private static Instant sortTimestamp(String primary, String fallback) {
    return parseInstant(firstNonBlank(primary, fallback)).orElse(Instant.EPOCH);
  }

  private static Optional<Instant> parseInstant(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(Instant.parse(value));
    } catch (Exception ignored) {
      return Optional.empty();
    }
  }

  private static String firstNonBlank(String primary, String fallback) {
    if (primary != null && !primary.isBlank()) {
      return primary;
    }
    return fallback;
  }

  private record SafeChain(int chainId, String code) {}

  private record OwnerChainDiscoveryResult(
      String ownerAddress, int chainId, List<String> safeAddresses, boolean failed) {}

  private record DiscoveryAccumulator(
      int chainId, String safeAddress, LinkedHashSet<String> ownerAddresses) {
    private DiscoveryAccumulator(int chainId, String safeAddress) {
      this(chainId, safeAddress, new LinkedHashSet<>());
    }
  }

  private record DiscoverySnapshot(
      List<SafeCollaborationDtos.DiscoveryItem> items,
      Map<String, List<Integer>> failedByOwner) {}

  private record SafesByOwnerPage(List<String> safeAddresses, Optional<Integer> nextOffset) {}

  private record QueueItem(String safeTxHash) {}

  private record QueuePage(List<QueueItem> items, Optional<Integer> nextOffset) {}

  private record TransactionDetail(
      String safeTxHash,
      String safeAddress,
      String to,
      String value,
      Integer nonce,
      int confirmationsRequired,
      List<String> confirmations,
      String submissionDate,
      String lastActivityAt) {}

  private record InboxDetailResult(SafeCollaborationDtos.InboxItem item, boolean failed) {}

  private record SafeInboxBatch(
      List<SafeCollaborationDtos.InboxItem> items, Set<String> failedSafeIds) {}
}
