package io.statusmvp.pricebackend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.statusmvp.pricebackend.model.safe.SafeCollaborationDtos;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.web3j.crypto.Keys;
import reactor.core.publisher.Mono;

class SafeCollaborationServiceTest {
  private static final String OWNER_1 = "0x1111111111111111111111111111111111111111";
  private static final String OWNER_2 = "0x2222222222222222222222222222222222222222";
  private static final String SAFE_A = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
  private static final String SAFE_B = "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
  private static final String OWNER_1_CHECKSUM = Keys.toChecksumAddress(OWNER_1);
  private static final String OWNER_2_CHECKSUM = Keys.toChecksumAddress(OWNER_2);
  private static final String SAFE_A_CHECKSUM = Keys.toChecksumAddress(SAFE_A);
  private static final String SAFE_B_CHECKSUM = Keys.toChecksumAddress(SAFE_B);
  private static final String SAFE_TX_1 =
      "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
  private static final String SAFE_TX_2 =
      "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
  private static final String OWNER_CHECKSUM = "0xE2eB8AF4c7E793f38A684404FF81a3ac69750bD4";
  private static final String OWNER_CHECKSUM_LOWER = OWNER_CHECKSUM.toLowerCase();
  private static final String OWNER_CHECKSUM_2 = "0xCad19E4fE39879197134456C0735C7E8c7a04c23";
  private static final String OWNER_CHECKSUM_2_LOWER = OWNER_CHECKSUM_2.toLowerCase();
  private static final String SAFE_CHECKSUM = "0x445C11cE36a13349Fb2A4D8Bfd4BD161b3B82BbA";
  private static final String SAFE_CHECKSUM_LOWER = SAFE_CHECKSUM.toLowerCase();

  private SafeTxServiceGatewayService gateway;
  private SafeCollaborationService service;

  @BeforeEach
  void setUp() {
    gateway = mock(SafeTxServiceGatewayService.class);
    service = new SafeCollaborationService(gateway, new ObjectMapper());
  }

  @Test
  void queryDiscoveryMergesOwnersAcrossSameSafe() {
    when(gateway.get(anyString(), anyString(), any(), anyString(), anyString(), any()))
        .thenAnswer(
            invocation -> {
              String chain = invocation.getArgument(0, String.class);
              String path = invocation.getArgument(1, String.class);
              if (!"eth".equals(chain)) {
                return Mono.just(ResponseEntity.status(404).body("{}"));
              }
              if (path.contains("/owners/" + OWNER_1_CHECKSUM + "/safes/")) {
                return Mono.just(
                    ResponseEntity.ok(
                        "{\"results\":[{\"address\":\"" + SAFE_A + "\"}],\"next\":null}"));
              }
              if (path.contains("/owners/" + OWNER_2_CHECKSUM + "/safes/")) {
                return Mono.just(
                    ResponseEntity.ok(
                        "{\"results\":[{\"address\":\""
                            + SAFE_A
                            + "\"},{\"address\":\""
                            + SAFE_B
                            + "\"}],\"next\":null}"));
              }
              return Mono.just(ResponseEntity.status(404).body("{}"));
            });

    SafeCollaborationDtos.DiscoveryResponse response =
        service
            .queryDiscoveredSafes(
                new SafeCollaborationDtos.DiscoveryRequest(List.of(OWNER_1, OWNER_2)),
                "127.0.0.1",
                "device-1")
            .block();

    assertNotNull(response);
    assertEquals(2, response.items().size());
    assertFalse(response.failedByOwner().containsKey(OWNER_1_CHECKSUM));
    SafeCollaborationDtos.DiscoveryItem first = response.items().get(0);
    assertEquals(1, first.chainId());
    assertEquals(SAFE_A_CHECKSUM, first.safeAddress());
    assertEquals(List.of(OWNER_1_CHECKSUM, OWNER_2_CHECKSUM), first.ownerAddresses());
  }

  @Test
  void queryDiscoveryConvertsLowercaseOwnerToChecksumForUpstream() {
    when(gateway.get(anyString(), anyString(), any(), anyString(), anyString(), any()))
        .thenAnswer(
            invocation -> {
              String chain = invocation.getArgument(0, String.class);
              String path = invocation.getArgument(1, String.class);
              if (!"bnb".equals(chain)) {
                return Mono.just(ResponseEntity.status(404).body("{}"));
              }
              if (path.contains("/owners/" + OWNER_CHECKSUM + "/safes/")) {
                return Mono.just(
                    ResponseEntity.ok(
                        "{\"results\":[{\"address\":\"" + SAFE_CHECKSUM + "\"}],\"next\":null}"));
              }
              if (path.contains("/owners/" + OWNER_CHECKSUM_LOWER + "/safes/")) {
                return Mono.just(ResponseEntity.status(422).body("{\"error\":\"checksum required\"}"));
              }
              return Mono.just(ResponseEntity.status(404).body("{}"));
            });

    SafeCollaborationDtos.DiscoveryResponse response =
        service
            .queryDiscoveredSafes(
                new SafeCollaborationDtos.DiscoveryRequest(List.of(OWNER_CHECKSUM_LOWER)),
                "127.0.0.1",
                "device-1")
            .block();

    assertNotNull(response);
    assertEquals(List.of(OWNER_CHECKSUM), response.ownerAddresses());
    assertEquals(1, response.items().size());
    assertEquals(SAFE_CHECKSUM, response.items().get(0).safeAddress());
  }

  @Test
  void queryInboxClassifiesConfirmationAndExecutionStates() {
    when(gateway.get(anyString(), anyString(), any(), anyString(), anyString(), any()))
        .thenAnswer(
            invocation -> {
              String chain = invocation.getArgument(0, String.class);
              String path = invocation.getArgument(1, String.class);
              @SuppressWarnings("unchecked")
              MultiValueMap<String, String> query = invocation.getArgument(2, MultiValueMap.class);

              if (!"eth".equals(chain)) {
                return Mono.just(ResponseEntity.status(404).body("{}"));
              }

              if (path.contains("/owners/" + OWNER_1_CHECKSUM + "/safes/")) {
                return Mono.just(
                    ResponseEntity.ok(
                        "{\"results\":[{\"address\":\"" + SAFE_A + "\"}],\"next\":null}"));
              }
              if (path.contains("/owners/" + OWNER_2_CHECKSUM + "/safes/")) {
                return Mono.just(
                    ResponseEntity.ok(
                        "{\"results\":[{\"address\":\"" + SAFE_A + "\"}],\"next\":null}"));
              }
              if (path.contains("/safes/" + SAFE_A_CHECKSUM + "/multisig-transactions/")) {
                assertEquals("false", query.getFirst("executed"));
                return Mono.just(
                    ResponseEntity.ok(
                        "{\"results\":[{\"safeTxHash\":\""
                            + SAFE_TX_1
                            + "\"},{\"safeTxHash\":\""
                            + SAFE_TX_2
                            + "\"}],\"next\":null}"));
              }
              if (path.contains("/multisig-transactions/" + SAFE_TX_1 + "/")) {
                return Mono.just(
                    ResponseEntity.ok(
                        "{"
                            + "\"safeTxHash\":\""
                            + SAFE_TX_1
                            + "\","
                            + "\"safe\":\""
                            + SAFE_A
                            + "\","
                            + "\"to\":\"0x3333333333333333333333333333333333333333\","
                            + "\"value\":\"1\","
                            + "\"nonce\":7,"
                            + "\"confirmationsRequired\":2,"
                            + "\"submissionDate\":\"2026-03-06T10:00:00Z\","
                            + "\"modified\":\"2026-03-06T10:01:00Z\","
                            + "\"confirmations\":[{\"owner\":\""
                            + OWNER_1
                            + "\"}]"
                            + "}"));
              }
              if (path.contains("/multisig-transactions/" + SAFE_TX_2 + "/")) {
                return Mono.just(
                    ResponseEntity.ok(
                        "{"
                            + "\"safeTxHash\":\""
                            + SAFE_TX_2
                            + "\","
                            + "\"safe\":\""
                            + SAFE_A
                            + "\","
                            + "\"to\":\"0x4444444444444444444444444444444444444444\","
                            + "\"value\":\"2\","
                            + "\"nonce\":8,"
                            + "\"confirmationsRequired\":2,"
                            + "\"submissionDate\":\"2026-03-06T11:00:00Z\","
                            + "\"modified\":\"2026-03-06T11:01:00Z\","
                            + "\"confirmations\":[{\"owner\":\""
                            + OWNER_1
                            + "\"},{\"owner\":\""
                            + OWNER_2
                            + "\"}]"
                            + "}"));
              }

              return Mono.just(ResponseEntity.status(404).body("{}"));
            });

    SafeCollaborationDtos.InboxResponse response =
        service
            .queryInbox(
                new SafeCollaborationDtos.InboxRequest(List.of(OWNER_1, OWNER_2), 10),
                "127.0.0.1",
                "device-1")
            .block();

    assertNotNull(response);
    assertEquals(2, response.items().size());
    assertFalse(response.truncated());
    assertFalse(response.failedByOwner().containsKey(OWNER_1_CHECKSUM));
    assertEquals("ready_to_execute", response.items().get(0).action());
    assertEquals(
        List.of(OWNER_1_CHECKSUM, OWNER_2_CHECKSUM), response.items().get(0).ownerAddresses());
    assertEquals(
        List.of(OWNER_1_CHECKSUM, OWNER_2_CHECKSUM),
        response.items().get(0).actionableOwnerAddresses());
    assertEquals("needs_confirmation", response.items().get(1).action());
    assertEquals(
        List.of(OWNER_1_CHECKSUM, OWNER_2_CHECKSUM), response.items().get(1).ownerAddresses());
    assertEquals(List.of(OWNER_2_CHECKSUM), response.items().get(1).actionableOwnerAddresses());
  }

  @Test
  void queryInboxIncludesDiscoveryFailuresInResponse() {
    when(gateway.get(anyString(), anyString(), any(), anyString(), anyString(), any()))
        .thenAnswer(
            invocation -> {
              String chain = invocation.getArgument(0, String.class);
              String path = invocation.getArgument(1, String.class);

              if ("eth".equals(chain) && path.contains("/owners/" + OWNER_1_CHECKSUM + "/safes/")) {
                return Mono.just(
                    ResponseEntity.ok(
                        "{\"results\":[{\"address\":\"" + SAFE_A + "\"}],\"next\":null}"));
              }
              if ("bnb".equals(chain) && path.contains("/owners/" + OWNER_1_CHECKSUM + "/safes/")) {
                return Mono.just(ResponseEntity.status(500).body("{\"error\":\"boom\"}"));
              }
              if (path.contains("/owners/")) {
                return Mono.just(ResponseEntity.status(404).body("{}"));
              }
              if (path.contains("/safes/" + SAFE_A_CHECKSUM + "/multisig-transactions/")) {
                return Mono.just(ResponseEntity.ok("{\"results\":[],\"next\":null}"));
              }
              return Mono.just(ResponseEntity.status(404).body("{}"));
            });

    SafeCollaborationDtos.InboxResponse response =
        service
            .queryInbox(
                new SafeCollaborationDtos.InboxRequest(List.of(OWNER_1), 10),
                "127.0.0.1",
                "device-1")
            .block();

    assertNotNull(response);
    assertEquals(List.of(56), response.failedByOwner().get(OWNER_1_CHECKSUM));
    assertEquals(List.of(), response.failedSafeIds());
  }

  @Test
  void queryInboxFetchesAdditionalQueuePages() {
    String safeTxHashPage1 =
        "0xcccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
    String safeTxHashPage2 =
        "0xdddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";

    when(gateway.get(anyString(), anyString(), any(), anyString(), anyString(), any()))
        .thenAnswer(
            invocation -> {
              String chain = invocation.getArgument(0, String.class);
              String path = invocation.getArgument(1, String.class);
              @SuppressWarnings("unchecked")
              MultiValueMap<String, String> query = invocation.getArgument(2, MultiValueMap.class);

              if (!"eth".equals(chain)) {
                return Mono.just(ResponseEntity.status(404).body("{}"));
              }
              if (path.contains("/owners/" + OWNER_1_CHECKSUM + "/safes/")) {
                return Mono.just(
                    ResponseEntity.ok(
                        "{\"results\":[{\"address\":\"" + SAFE_A + "\"}],\"next\":null}"));
              }
              if (path.contains("/safes/" + SAFE_A_CHECKSUM + "/multisig-transactions/")) {
                String offset = query.getFirst("offset");
                if ("0".equals(offset)) {
                  return Mono.just(
                      ResponseEntity.ok(
                          "{\"results\":[{\"safeTxHash\":\""
                              + safeTxHashPage1
                              + "\"}],\"next\":\"https://safe.example/api/v2/safes/"
                              + SAFE_A_CHECKSUM
                              + "/multisig-transactions/?offset=10&limit=10\"}"));
                }
                if ("10".equals(offset)) {
                  return Mono.just(
                      ResponseEntity.ok(
                          "{\"results\":[{\"safeTxHash\":\""
                              + safeTxHashPage2
                              + "\"}],\"next\":null}"));
                }
              }
              if (path.contains("/multisig-transactions/" + safeTxHashPage1 + "/")) {
                return Mono.just(
                    ResponseEntity.ok(
                        "{"
                            + "\"safeTxHash\":\""
                            + safeTxHashPage1
                            + "\","
                            + "\"safe\":\""
                            + SAFE_A
                            + "\","
                            + "\"to\":\"0x3333333333333333333333333333333333333333\","
                            + "\"value\":\"1\","
                            + "\"nonce\":1,"
                            + "\"confirmationsRequired\":2,"
                            + "\"submissionDate\":\"2026-03-06T10:00:00Z\","
                            + "\"modified\":\"2026-03-06T10:01:00Z\","
                            + "\"confirmations\":[]"
                            + "}"));
              }
              if (path.contains("/multisig-transactions/" + safeTxHashPage2 + "/")) {
                return Mono.just(
                    ResponseEntity.ok(
                        "{"
                            + "\"safeTxHash\":\""
                            + safeTxHashPage2
                            + "\","
                            + "\"safe\":\""
                            + SAFE_A
                            + "\","
                            + "\"to\":\"0x4444444444444444444444444444444444444444\","
                            + "\"value\":\"2\","
                            + "\"nonce\":2,"
                            + "\"confirmationsRequired\":2,"
                            + "\"submissionDate\":\"2026-03-06T11:00:00Z\","
                            + "\"modified\":\"2026-03-06T11:01:00Z\","
                            + "\"confirmations\":[]"
                            + "}"));
              }
              return Mono.just(ResponseEntity.status(404).body("{}"));
            });

    SafeCollaborationDtos.InboxResponse response =
        service
            .queryInbox(
                new SafeCollaborationDtos.InboxRequest(List.of(OWNER_1), 10),
                "127.0.0.1",
                "device-1")
            .block();

    assertNotNull(response);
    assertEquals(2, response.items().size());
    assertIterableEquals(
        List.of(safeTxHashPage2, safeTxHashPage1),
        response.items().stream().map(SafeCollaborationDtos.InboxItem::safeTxHash).toList());
  }

  @Test
  void queryInboxItemsForSafesConvertsLowercaseSafeAndOwnersToChecksumForUpstream() {
    when(gateway.get(anyString(), anyString(), any(), anyString(), anyString(), any()))
        .thenAnswer(
            invocation -> {
              String chain = invocation.getArgument(0, String.class);
              String path = invocation.getArgument(1, String.class);
              @SuppressWarnings("unchecked")
              MultiValueMap<String, String> query = invocation.getArgument(2, MultiValueMap.class);

              if (!"bnb".equals(chain)) {
                return Mono.just(ResponseEntity.status(404).body("{}"));
              }
              if (path.contains("/safes/" + SAFE_CHECKSUM + "/multisig-transactions/")) {
                assertEquals("false", query.getFirst("executed"));
                return Mono.just(
                    ResponseEntity.ok(
                        "{\"results\":[{\"safeTxHash\":\"" + SAFE_TX_1 + "\"}],\"next\":null}"));
              }
              if (path.contains("/safes/" + SAFE_CHECKSUM_LOWER + "/multisig-transactions/")) {
                return Mono.just(ResponseEntity.status(422).body("{\"error\":\"checksum required\"}"));
              }
              if (path.contains("/multisig-transactions/" + SAFE_TX_1 + "/")) {
                return Mono.just(
                    ResponseEntity.ok(
                        "{"
                            + "\"safeTxHash\":\""
                            + SAFE_TX_1
                            + "\","
                            + "\"safe\":\""
                            + SAFE_CHECKSUM
                            + "\","
                            + "\"to\":\"0x3333333333333333333333333333333333333333\","
                            + "\"value\":\"1\","
                            + "\"nonce\":7,"
                            + "\"confirmationsRequired\":2,"
                            + "\"submissionDate\":\"2026-03-06T10:00:00Z\","
                            + "\"modified\":\"2026-03-06T10:01:00Z\","
                            + "\"confirmations\":[{\"owner\":\""
                            + OWNER_CHECKSUM
                            + "\"}]"
                            + "}"));
              }
              return Mono.just(ResponseEntity.status(404).body("{}"));
            });

    List<SafeCollaborationDtos.InboxItem> response =
        service
            .queryInboxItemsForSafes(
                List.of(
                    new SafeCollaborationDtos.DiscoveryItem(
                        56, SAFE_CHECKSUM_LOWER, List.of(OWNER_CHECKSUM_LOWER, OWNER_CHECKSUM_2_LOWER))),
                10,
                "127.0.0.1",
                "device-1")
            .block();

    assertNotNull(response);
    assertEquals(1, response.size());
    assertEquals(SAFE_CHECKSUM, response.get(0).safeAddress());
    assertEquals(
        List.of(OWNER_CHECKSUM, Keys.toChecksumAddress(OWNER_CHECKSUM_2)),
        response.get(0).ownerAddresses());
    assertEquals(List.of(Keys.toChecksumAddress(OWNER_CHECKSUM_2)), response.get(0).actionableOwnerAddresses());
  }

  @Test
  void queryNotificationCandidatesIncludesPendingAndFailedExecutionTransactions() {
    when(gateway.get(anyString(), anyString(), any(), anyString(), anyString(), any()))
        .thenAnswer(
            invocation -> {
              String chain = invocation.getArgument(0, String.class);
              String path = invocation.getArgument(1, String.class);
              @SuppressWarnings("unchecked")
              MultiValueMap<String, String> query = invocation.getArgument(2, MultiValueMap.class);

              if (!"eth".equals(chain)) {
                return Mono.just(ResponseEntity.status(404).body("{}"));
              }
              if (path.contains("/safes/" + SAFE_A_CHECKSUM + "/multisig-transactions/")) {
                if ("false".equals(query.getFirst("executed"))) {
                  return Mono.just(
                      ResponseEntity.ok(
                          "{\"results\":[{\"safeTxHash\":\"" + SAFE_TX_1 + "\"}],\"next\":null}"));
                }
                if ("true".equals(query.getFirst("executed"))) {
                  return Mono.just(
                      ResponseEntity.ok(
                          "{\"results\":[{\"safeTxHash\":\"" + SAFE_TX_2 + "\"}],\"next\":null}"));
                }
              }
              if (path.contains("/multisig-transactions/" + SAFE_TX_1 + "/")) {
                return Mono.just(
                    ResponseEntity.ok(
                        "{"
                            + "\"safeTxHash\":\""
                            + SAFE_TX_1
                            + "\","
                            + "\"safe\":\""
                            + SAFE_A
                            + "\","
                            + "\"confirmationsRequired\":2,"
                            + "\"isExecuted\":false,"
                            + "\"submissionDate\":\"2026-03-06T10:00:00Z\","
                            + "\"modified\":\"2026-03-06T10:01:00Z\","
                            + "\"confirmations\":[{\"owner\":\""
                            + OWNER_1
                            + "\"}]"
                            + "}"));
              }
              if (path.contains("/multisig-transactions/" + SAFE_TX_2 + "/")) {
                return Mono.just(
                    ResponseEntity.ok(
                        "{"
                            + "\"safeTxHash\":\""
                            + SAFE_TX_2
                            + "\","
                            + "\"safe\":\""
                            + SAFE_A
                            + "\","
                            + "\"confirmationsRequired\":2,"
                            + "\"isExecuted\":true,"
                            + "\"isSuccessful\":false,"
                            + "\"transactionHash\":\"0xcccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc\","
                            + "\"submissionDate\":\"2026-03-06T11:00:00Z\","
                            + "\"modified\":\"2026-03-06T11:01:00Z\","
                            + "\"confirmations\":[{\"owner\":\""
                            + OWNER_1
                            + "\"},{\"owner\":\""
                            + OWNER_2
                            + "\"}]"
                            + "}"));
              }
              return Mono.just(ResponseEntity.status(404).body("{}"));
            });

    List<SafeCollaborationService.SafeNotificationCandidate> response =
        service
            .queryNotificationCandidatesForSafes(
                List.of(
                    new SafeCollaborationDtos.DiscoveryItem(
                        1, SAFE_A_CHECKSUM, List.of(OWNER_1_CHECKSUM, OWNER_2_CHECKSUM))),
                10,
                "127.0.0.1",
                "device-1")
            .block();

    assertNotNull(response);
    assertEquals(2, response.size());
    assertEquals(SAFE_TX_2, response.get(0).safeTxHash());
    assertEquals(true, response.get(0).executionFailed());
    assertEquals(SAFE_TX_1, response.get(1).safeTxHash());
    assertEquals(false, response.get(1).executionFailed());
  }

  @Test
  void queryInboxDoesNotTreatFilteredTransactionsAsDetailFailures() {
    when(gateway.get(anyString(), anyString(), any(), anyString(), anyString(), any()))
        .thenAnswer(
            invocation -> {
              String chain = invocation.getArgument(0, String.class);
              String path = invocation.getArgument(1, String.class);

              if (!"eth".equals(chain)) {
                return Mono.just(ResponseEntity.status(404).body("{}"));
              }
              if (path.contains("/owners/" + OWNER_1_CHECKSUM + "/safes/")) {
                return Mono.just(
                    ResponseEntity.ok(
                        "{\"results\":[{\"address\":\"" + SAFE_A + "\"}],\"next\":null}"));
              }
              if (path.contains("/safes/" + SAFE_A_CHECKSUM + "/multisig-transactions/")) {
                return Mono.just(
                    ResponseEntity.ok(
                        "{\"results\":[{\"safeTxHash\":\"" + SAFE_TX_1 + "\"}],\"next\":null}"));
              }
              if (path.contains("/multisig-transactions/" + SAFE_TX_1 + "/")) {
                return Mono.just(
                    ResponseEntity.ok(
                        "{"
                            + "\"safeTxHash\":\""
                            + SAFE_TX_1
                            + "\","
                            + "\"safe\":\""
                            + SAFE_A
                            + "\","
                            + "\"to\":\"0x3333333333333333333333333333333333333333\","
                            + "\"value\":\"1\","
                            + "\"nonce\":7,"
                            + "\"confirmationsRequired\":2,"
                            + "\"submissionDate\":\"2026-03-06T10:00:00Z\","
                            + "\"modified\":\"2026-03-06T10:01:00Z\","
                            + "\"confirmations\":[{\"owner\":\""
                            + OWNER_1
                            + "\"}]"
                            + "}"));
              }
              return Mono.just(ResponseEntity.status(404).body("{}"));
            });

    SafeCollaborationDtos.InboxResponse response =
        service
            .queryInbox(
                new SafeCollaborationDtos.InboxRequest(List.of(OWNER_1), 10),
                "127.0.0.1",
                "device-1")
            .block();

    assertNotNull(response);
    assertEquals(List.of(), response.items());
    assertEquals(List.of(), response.failedSafeIds());
  }
}
