package io.statusmvp.pricebackend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.statusmvp.pricebackend.model.safe.SafeNotificationDtos;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class SafeNotificationServiceTest {
  private static final String SAFE = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
  private static final String SAFE_TX_HASH =
      "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
  private static final String EXECUTION_TX_HASH =
      "0xcccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";

  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
  @SuppressWarnings("unchecked")
  private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
  @SuppressWarnings("unchecked")
  private final SetOperations<String, String> setOps = mock(SetOperations.class);
  private SafeNotificationService service;

  @BeforeEach
  void setUp() {
    when(redis.opsForValue()).thenReturn(valueOps);
    when(redis.opsForSet()).thenReturn(setOps);
    when(setOps.members(anyString())).thenReturn(Collections.emptySet());
    doAnswer(invocation -> null).when(valueOps).set(anyString(), anyString(), any(Duration.class));

    service =
        new SafeNotificationService(
            redis,
            new ObjectMapper(),
            mock(SafeCollaborationService.class),
            true,
            50,
            20,
            1209600,
            true,
            "firebase-project",
            "firebase@example.test",
            "not-parsed-during-register",
            false,
            "",
            "",
            "",
            "",
            true);
  }

  @Test
  void keepsUnsupportedCloudMessagingProviderOnLocalPullEvenWhenFcmIsConfigured() {
    SafeNotificationDtos.RegisterResponse response =
        service
            .register(
                new SafeNotificationDtos.RegisterRequest(
                    "device-1",
                    "android",
                    "vendor-token",
                    "hms",
                    List.of(
                        new SafeNotificationDtos.SafeSubscription(
                            1,
                            "0x0000000000000000000000000000000000000001",
                            List.of("0x0000000000000000000000000000000000000002"),
                            List.of(SafeNotificationService.TYPE_CONFIRMATION_REQUEST)))),
                null)
            .block();

    assertEquals("pull_local_notification", response.transport());
  }

  @Test
  void usesRemotePushOnlyForExplicitFcmProviderWithConfiguredFcm() {
    SafeNotificationDtos.RegisterResponse response =
        service
            .register(
                new SafeNotificationDtos.RegisterRequest(
                    "device-1",
                    "android",
                    "fcm-token",
                    "fcm",
                    List.of(
                        new SafeNotificationDtos.SafeSubscription(
                            1,
                            "0x0000000000000000000000000000000000000001",
                            List.of("0x0000000000000000000000000000000000000002"),
                            List.of(SafeNotificationService.TYPE_CONFIRMATION_REQUEST)))),
                null)
            .block();

    assertEquals("remote_push", response.transport());
  }

  @Test
  void newPendingTransactionCanNotifyProposalOrConfirmationRequest() {
    List<String> types =
        SafeNotificationService.notificationTypesForTransition(
            candidate(0, 2, false, ""), SafeNotificationService.parseNotificationState(null));

    assertEquals(
        List.of(
            SafeNotificationService.TYPE_TRANSACTION_PROPOSED,
            SafeNotificationService.TYPE_CONFIRMATION_REQUEST),
        types);
  }

  @Test
  void increasedPendingConfirmationsCanNotifyConfirmationProgress() {
    SafeNotificationService.NotificationState previous =
        SafeNotificationService.parseNotificationState(
            SafeNotificationService.buildNotificationStateToken(
                SafeNotificationService.TYPE_TRANSACTION_PROPOSED, "pending", 0, 2, ""));

    List<String> types =
        SafeNotificationService.notificationTypesForTransition(candidate(1, 2, false, ""), previous);

    assertEquals(
        List.of(
            SafeNotificationService.TYPE_TRANSACTION_CONFIRMED,
            SafeNotificationService.TYPE_CONFIRMATION_REQUEST),
        types);
  }

  @Test
  void thresholdTransitionNotifiesReadyOnlyOnce() {
    SafeNotificationService.NotificationState previousPending =
        SafeNotificationService.parseNotificationState(
            SafeNotificationService.buildNotificationStateToken(
                SafeNotificationService.TYPE_TRANSACTION_CONFIRMED, "pending", 1, 2, ""));

    List<String> readyTypes =
        SafeNotificationService.notificationTypesForTransition(
            candidate(2, 2, false, ""), previousPending);

    SafeNotificationService.NotificationState previousReady =
        SafeNotificationService.parseNotificationState(
            SafeNotificationService.buildNotificationStateToken(
                SafeNotificationService.TYPE_READY_TO_EXECUTE, "ready", 2, 2, ""));
    List<String> repeatedTypes =
        SafeNotificationService.notificationTypesForTransition(
            candidate(2, 2, false, ""), previousReady);

    assertEquals(List.of(SafeNotificationService.TYPE_READY_TO_EXECUTE), readyTypes);
    assertEquals(List.of(), repeatedTypes);
  }

  @Test
  void failedExecutionNotifiesFailedOnlyOnce() {
    SafeNotificationService.NotificationState previousPending =
        SafeNotificationService.parseNotificationState(
            SafeNotificationService.buildNotificationStateToken(
                SafeNotificationService.TYPE_READY_TO_EXECUTE, "ready", 2, 2, ""));

    List<String> failedTypes =
        SafeNotificationService.notificationTypesForTransition(
            candidate(2, 2, true, EXECUTION_TX_HASH), previousPending);

    SafeNotificationService.NotificationState previousFailed =
        SafeNotificationService.parseNotificationState(
            SafeNotificationService.buildNotificationStateToken(
                SafeNotificationService.TYPE_EXECUTION_FAILED,
                "failed",
                2,
                2,
                EXECUTION_TX_HASH));
    List<String> repeatedTypes =
        SafeNotificationService.notificationTypesForTransition(
            candidate(2, 2, true, EXECUTION_TX_HASH), previousFailed);

    assertEquals(List.of(SafeNotificationService.TYPE_EXECUTION_FAILED), failedTypes);
    assertEquals(List.of(), repeatedTypes);
  }

  private static SafeCollaborationService.SafeNotificationCandidate candidate(
      int submitted, int required, boolean failed, String transactionHash) {
    return new SafeCollaborationService.SafeNotificationCandidate(
        1,
        SAFE,
        SAFE_TX_HASH,
        7,
        submitted,
        required,
        List.of(),
        transactionHash,
        failed,
        "2026-03-06T10:00:00Z",
        "2026-03-06T10:01:00Z");
  }
}
