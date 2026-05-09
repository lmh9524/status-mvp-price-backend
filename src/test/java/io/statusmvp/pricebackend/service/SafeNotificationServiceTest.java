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
                            List.of("CONFIRMATION_REQUEST")))),
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
                            List.of("CONFIRMATION_REQUEST")))),
                null)
            .block();

    assertEquals("remote_push", response.transport());
  }
}
