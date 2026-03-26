package io.statusmvp.pricebackend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.statusmvp.pricebackend.model.safe.SafeCollaborationDtos;
import io.statusmvp.pricebackend.model.safe.SafeNotificationDtos;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@Service
public class SafeNotificationService {
  private static final Logger log = LoggerFactory.getLogger(SafeNotificationService.class);

  private static final Pattern EVM_ADDRESS_PATTERN = Pattern.compile("^0x[0-9a-fA-F]{40}$");
  private static final String DEVICES_KEY = "safe:notif:devices";
  private static final String TRANSPORT_PULL_LOCAL = "pull_local_notification";
  private static final String TRANSPORT_REMOTE_PUSH = "remote_push";
  private static final String FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
  private static final List<String> DEFAULT_NOTIFICATION_TYPES =
      List.of("CONFIRMATION_REQUEST", "READY_TO_EXECUTE");

  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;
  private final SafeCollaborationService collaborationService;
  private final HttpClient httpClient;
  private final boolean pollingEnabled;
  private final int perDeviceInboxLimit;
  private final int pullDefaultLimit;
  private final long queueTtlSeconds;
  private final boolean fcmEnabled;
  private final String fcmProjectId;
  private final String fcmClientEmail;
  private final String fcmPrivateKeyPem;
  private final boolean apnsEnabled;
  private final String apnsTeamId;
  private final String apnsKeyId;
  private final String apnsBundleId;
  private final String apnsPrivateKeyPem;
  private final boolean apnsUseSandbox;
  private volatile String cachedFcmAccessToken;
  private volatile Instant cachedFcmAccessTokenExpiresAt = Instant.EPOCH;
  private volatile String cachedApnsJwt;
  private volatile Instant cachedApnsJwtExpiresAt = Instant.EPOCH;

  public SafeNotificationService(
      StringRedisTemplate redis,
      ObjectMapper objectMapper,
      SafeCollaborationService collaborationService,
      @Value("${app.safe.notifications.enabled:true}") boolean pollingEnabled,
      @Value("${app.safe.notifications.inboxLimit:50}") int perDeviceInboxLimit,
      @Value("${app.safe.notifications.pullDefaultLimit:20}") int pullDefaultLimit,
      @Value("${app.safe.notifications.queueTtlSeconds:1209600}") long queueTtlSeconds,
      @Value("${app.safe.notifications.remote.fcm.enabled:false}") boolean fcmEnabled,
      @Value("${app.safe.notifications.remote.fcm.projectId:}") String fcmProjectId,
      @Value("${app.safe.notifications.remote.fcm.clientEmail:}") String fcmClientEmail,
      @Value("${app.safe.notifications.remote.fcm.privateKeyPem:}") String fcmPrivateKeyPem,
      @Value("${app.safe.notifications.remote.apns.enabled:false}") boolean apnsEnabled,
      @Value("${app.safe.notifications.remote.apns.teamId:}") String apnsTeamId,
      @Value("${app.safe.notifications.remote.apns.keyId:}") String apnsKeyId,
      @Value("${app.safe.notifications.remote.apns.bundleId:}") String apnsBundleId,
      @Value("${app.safe.notifications.remote.apns.privateKeyPem:}") String apnsPrivateKeyPem,
      @Value("${app.safe.notifications.remote.apns.useSandbox:true}") boolean apnsUseSandbox) {
    this.redis = redis;
    this.objectMapper = objectMapper;
    this.collaborationService = collaborationService;
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    this.pollingEnabled = pollingEnabled;
    this.perDeviceInboxLimit = Math.max(1, Math.min(100, perDeviceInboxLimit));
    this.pullDefaultLimit = Math.max(1, Math.min(100, pullDefaultLimit));
    this.queueTtlSeconds = Math.max(60, queueTtlSeconds);
    this.fcmEnabled = fcmEnabled;
    this.fcmProjectId = normalizeToken(fcmProjectId);
    this.fcmClientEmail = normalizeToken(fcmClientEmail);
    this.fcmPrivateKeyPem = normalizePem(fcmPrivateKeyPem);
    this.apnsEnabled = apnsEnabled;
    this.apnsTeamId = normalizeToken(apnsTeamId);
    this.apnsKeyId = normalizeToken(apnsKeyId);
    this.apnsBundleId = normalizeToken(apnsBundleId);
    this.apnsPrivateKeyPem = normalizePem(apnsPrivateKeyPem);
    this.apnsUseSandbox = apnsUseSandbox;
  }

  @PostConstruct
  void logConfigurationSummary() {
    String fcmState = fcmConfigState();
    String apnsState = apnsConfigState();
    log.info(
        "safe.notifications.config_summary pollingEnabled={} queueTtlSeconds={} inboxLimit={} pullDefaultLimit={} fcmState={} apnsState={}",
        pollingEnabled,
        queueTtlSeconds,
        perDeviceInboxLimit,
        pullDefaultLimit,
        fcmState,
        apnsState);
    if (!"ready".equals(fcmState)) {
      log.warn(
          "safe.notifications.android_push_unavailable state={} fallbackTransport={}",
          fcmState,
          TRANSPORT_PULL_LOCAL);
    }
    if (!"ready".equals(apnsState)) {
      log.warn(
          "safe.notifications.ios_push_unavailable state={} fallbackTransport={}",
          apnsState,
          TRANSPORT_PULL_LOCAL);
    }
  }

  public Mono<SafeNotificationDtos.RegisterResponse> register(
      SafeNotificationDtos.RegisterRequest request, String fallbackDeviceUuid) {
    return Mono.fromCallable(
            () -> {
              String deviceUuid = resolveDeviceUuid(request == null ? null : request.deviceUuid(), fallbackDeviceUuid);
              String deviceType = normalizeDeviceType(request == null ? null : request.deviceType());
              String cloudMessagingToken = normalizeToken(request == null ? null : request.cloudMessagingToken());
              List<SubscriptionRecord> subscriptions = normalizeSubscriptions(request == null ? null : request.safes());

              DeviceRecord deviceRecord =
                  new DeviceRecord(deviceUuid, deviceType, cloudMessagingToken, Instant.now().toString());

              upsertSubscriptions(deviceUuid, subscriptions);
              saveDeviceRecord(deviceRecord);

              return new SafeNotificationDtos.RegisterResponse(
                  deviceUuid, resolveTransport(deviceRecord), deviceRecord.updatedAt(), subscriptions.size());
            })
        .subscribeOn(Schedulers.boundedElastic());
  }

  public Mono<SafeNotificationDtos.PullResponse> pull(
      SafeNotificationDtos.PullRequest request, String fallbackDeviceUuid) {
    return Mono.fromCallable(
            () -> {
              String deviceUuid = resolveDeviceUuid(request == null ? null : request.deviceUuid(), fallbackDeviceUuid);
              int limit = request == null || request.limit() == null ? pullDefaultLimit : Math.max(1, Math.min(100, request.limit()));
              List<SafeNotificationDtos.NotificationItem> items = pullNotifications(deviceUuid, limit);
              return new SafeNotificationDtos.PullResponse(
                  deviceUuid, resolveTransport(loadDeviceRecord(deviceUuid)), Instant.now().toString(), items);
            })
        .subscribeOn(Schedulers.boundedElastic());
  }

  public Mono<SafeNotificationDtos.ClearSubscriptionsResponse> clearSubscriptions(String fallbackDeviceUuid) {
    return Mono.fromCallable(
            () -> {
              String deviceUuid = resolveDeviceUuid(null, fallbackDeviceUuid);
              int removedCount = removeSubscriptions(deviceUuid);
              redis.delete(deviceQueueKey(deviceUuid));
              redis.opsForSet().remove(DEVICES_KEY, deviceUuid);
              return new SafeNotificationDtos.ClearSubscriptionsResponse(deviceUuid, removedCount);
            })
        .subscribeOn(Schedulers.boundedElastic());
  }

  public Mono<SafeNotificationDtos.DeleteDeviceResponse> deleteDevice(String deviceUuid) {
    return Mono.fromCallable(
            () -> new SafeNotificationDtos.DeleteDeviceResponse(deviceUuid, deleteDeviceInternal(deviceUuid)))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @Scheduled(fixedDelayString = "${app.safe.notifications.pollDelayMs:45000}")
  public void pollSubscribedSafes() {
    if (!pollingEnabled) {
      return;
    }
    try {
      pollOnce();
    } catch (Exception error) {
      log.warn("safe.notifications.poll_failed error={}", error.getMessage());
    }
  }

  void pollOnce() {
    Set<String> deviceIds = safeSetMembers(DEVICES_KEY);
    for (String deviceUuid : deviceIds) {
      try {
        processDevice(deviceUuid);
      } catch (Exception error) {
        log.warn("safe.notifications.device_poll_failed deviceUuid={} error={}", deviceUuid, error.getMessage());
      }
    }
  }

  private void processDevice(String deviceUuid) {
    DeviceRecord deviceRecord = loadDeviceRecord(deviceUuid);
    List<SubscriptionRecord> subscriptions = loadSubscriptions(deviceUuid);
    if (subscriptions.isEmpty()) {
      clearDeviceState(deviceUuid);
      redis.opsForSet().remove(DEVICES_KEY, deviceUuid);
      return;
    }

    List<SafeCollaborationDtos.DiscoveryItem> items =
        subscriptions.stream()
            .map(
                subscription ->
                    new SafeCollaborationDtos.DiscoveryItem(
                        subscription.chainId(), subscription.safeAddress(), subscription.ownerAddresses()))
            .toList();

    List<SafeCollaborationDtos.InboxItem> inboxItems =
        collaborationService
            .queryInboxItemsForSafes(items, perDeviceInboxLimit, "", "safe-notifier:" + deviceUuid)
            .blockOptional(Duration.ofSeconds(20))
            .orElse(List.of());

    Map<String, String> previousStates = loadDeviceStates(deviceUuid);
    Map<String, String> currentStates = new LinkedHashMap<>();
    Map<String, SubscriptionRecord> subscriptionBySafeId = new LinkedHashMap<>();
    for (SubscriptionRecord subscription : subscriptions) {
      subscriptionBySafeId.put(safeId(subscription.chainId(), subscription.safeAddress()), subscription);
    }

    for (SafeCollaborationDtos.InboxItem item : inboxItems) {
      String notificationType = notificationTypeForAction(item.action());
      if (notificationType == null) {
        continue;
      }
      SubscriptionRecord subscription =
          subscriptionBySafeId.get(safeId(item.chainId(), item.safeAddress()));
      if (subscription == null || !subscription.notificationTypes().contains(notificationType)) {
        continue;
      }

      String stateKey = stateKey(item.chainId(), item.safeTxHash());
      currentStates.put(stateKey, notificationType);
      String previous = previousStates.get(stateKey);
      if (!notificationType.equals(previous)) {
        NotificationRecord record =
            new NotificationRecord(
                UUID.randomUUID().toString(),
                notificationType,
                item.chainId(),
                item.safeAddress(),
                item.safeTxHash(),
                item.confirmationsSubmitted(),
                item.confirmationsRequired(),
                Instant.now().toString());
        if (!dispatchRemoteNotification(deviceRecord, record)) {
          enqueueNotification(deviceUuid, record);
        }
      }
    }

    saveDeviceStates(deviceUuid, currentStates);
  }

  private void upsertSubscriptions(String deviceUuid, List<SubscriptionRecord> nextSubscriptions) {
    if (nextSubscriptions.isEmpty()) {
      removeSubscriptions(deviceUuid);
      redis.opsForSet().remove(DEVICES_KEY, deviceUuid);
      return;
    }

    redis.opsForSet().add(DEVICES_KEY, deviceUuid);

    String subscriptionsKey = deviceSubscriptionsKey(deviceUuid);
    Set<String> existingSafeIds = safeSetMembers(subscriptionsKey);
    Set<String> nextSafeIds = new LinkedHashSet<>();

    for (SubscriptionRecord subscription : nextSubscriptions) {
      String safeId = safeId(subscription.chainId(), subscription.safeAddress());
      nextSafeIds.add(safeId);
      redis.opsForSet().add(subscriptionsKey, safeId);
      redis.opsForSet().add(safeDevicesKey(safeId), deviceUuid);
      setJson(deviceSubscriptionKey(deviceUuid, safeId), subscription, queueTtlSeconds);
    }

    for (String existingSafeId : existingSafeIds) {
      if (nextSafeIds.contains(existingSafeId)) {
        continue;
      }
      redis.opsForSet().remove(subscriptionsKey, existingSafeId);
      redis.opsForSet().remove(safeDevicesKey(existingSafeId), deviceUuid);
      redis.delete(deviceSubscriptionKey(deviceUuid, existingSafeId));
    }
  }

  private int removeSubscriptions(String deviceUuid) {
    String subsKey = deviceSubscriptionsKey(deviceUuid);
    Set<String> existingSafeIds = safeSetMembers(subsKey);
    int removedCount = existingSafeIds.size();
    for (String safeId : existingSafeIds) {
      redis.opsForSet().remove(safeDevicesKey(safeId), deviceUuid);
      redis.delete(deviceSubscriptionKey(deviceUuid, safeId));
    }
    redis.delete(subsKey);
    redis.delete(deviceStateKey(deviceUuid));
    return removedCount;
  }

  private boolean deleteDeviceInternal(String deviceUuid) {
    String normalizedDeviceUuid = normalizeDeviceUuid(deviceUuid);
    if (normalizedDeviceUuid == null) {
      return false;
    }
    removeSubscriptions(normalizedDeviceUuid);
    redis.delete(deviceQueueKey(normalizedDeviceUuid));
    redis.delete(deviceKey(normalizedDeviceUuid));
    redis.opsForSet().remove(DEVICES_KEY, normalizedDeviceUuid);
    return true;
  }

  private void saveDeviceRecord(DeviceRecord deviceRecord) {
    setJson(deviceKey(deviceRecord.deviceUuid()), deviceRecord, queueTtlSeconds);
    redis.opsForSet().add(DEVICES_KEY, deviceRecord.deviceUuid());
  }

  private DeviceRecord loadDeviceRecord(String deviceUuid) {
    return readJson(deviceKey(deviceUuid), DeviceRecord.class);
  }

  private List<SubscriptionRecord> loadSubscriptions(String deviceUuid) {
    Set<String> safeIds = safeSetMembers(deviceSubscriptionsKey(deviceUuid));
    List<SubscriptionRecord> subscriptions = new ArrayList<>();
    for (String safeId : safeIds) {
      SubscriptionRecord subscription =
          readJson(deviceSubscriptionKey(deviceUuid, safeId), SubscriptionRecord.class);
      if (subscription != null) {
        subscriptions.add(subscription);
      }
    }
    return subscriptions;
  }

  private void clearDeviceState(String deviceUuid) {
    redis.delete(deviceStateKey(deviceUuid));
  }

  private Map<String, String> loadDeviceStates(String deviceUuid) {
    try {
      Map<Object, Object> raw = redis.opsForHash().entries(deviceStateKey(deviceUuid));
      if (raw == null || raw.isEmpty()) {
        return Map.of();
      }
      Map<String, String> next = new LinkedHashMap<>();
      for (Map.Entry<Object, Object> entry : raw.entrySet()) {
        String key = String.valueOf(entry.getKey()).trim();
        String value = String.valueOf(entry.getValue()).trim();
        if (!key.isEmpty() && !value.isEmpty()) {
          next.put(key, value);
        }
      }
      return next;
    } catch (Exception ignored) {
      return Map.of();
    }
  }

  private void saveDeviceStates(String deviceUuid, Map<String, String> states) {
    String key = deviceStateKey(deviceUuid);
    try {
      Set<Object> previousKeys = redis.opsForHash().keys(key);
      if (previousKeys != null && !previousKeys.isEmpty()) {
        List<Object> remove = new ArrayList<>();
        for (Object previousKey : previousKeys) {
          if (!states.containsKey(String.valueOf(previousKey))) {
            remove.add(previousKey);
          }
        }
        if (!remove.isEmpty()) {
          redis.opsForHash().delete(key, remove.toArray());
        }
      }
      if (!states.isEmpty()) {
        redis.opsForHash().putAll(key, new LinkedHashMap<>(states));
        redis.expire(key, Duration.ofSeconds(queueTtlSeconds));
      } else {
        redis.delete(key);
      }
    } catch (Exception ignored) {
      // ignore notification state failures
    }
  }

  private void enqueueNotification(String deviceUuid, NotificationRecord record) {
    try {
      redis.opsForList().rightPush(deviceQueueKey(deviceUuid), objectMapper.writeValueAsString(record));
      redis.expire(deviceQueueKey(deviceUuid), Duration.ofSeconds(queueTtlSeconds));
    } catch (Exception error) {
      log.warn("safe.notifications.enqueue_failed deviceUuid={} error={}", deviceUuid, error.getMessage());
    }
  }

  private List<SafeNotificationDtos.NotificationItem> pullNotifications(String deviceUuid, int limit) {
    try {
      int scanLimit = Math.max(limit, Math.min(200, limit * 4));
      List<String> raw = redis.opsForList().range(deviceQueueKey(deviceUuid), 0, scanLimit - 1);
      if (raw == null || raw.isEmpty()) {
        return List.of();
      }
      Map<String, String> currentStates = loadDeviceStates(deviceUuid);
      List<SafeNotificationDtos.NotificationItem> items = new ArrayList<>();
      int consumedCount = 0;
      for (String item : raw) {
        consumedCount += 1;
        NotificationRecord record = readJsonString(item, NotificationRecord.class);
        if (record == null) {
          continue;
        }
        String currentState = currentStates.get(stateKey(record.chainId(), record.safeTxHash()));
        if (!record.notificationType().equals(currentState)) {
          continue;
        }
        items.add(
            new SafeNotificationDtos.NotificationItem(
                record.id(),
                record.notificationType(),
                record.chainId(),
                record.safeAddress(),
                record.safeTxHash(),
                record.confirmationsSubmitted(),
                record.confirmationsRequired(),
                record.createdAt()));
        if (items.size() >= limit) {
          break;
        }
      }
      if (consumedCount > 0) {
        redis.opsForList().trim(deviceQueueKey(deviceUuid), consumedCount, -1);
      }
      return List.copyOf(items);
    } catch (Exception ignored) {
      return List.of();
    }
  }

  private boolean dispatchRemoteNotification(DeviceRecord deviceRecord, NotificationRecord record) {
    if (deviceRecord == null) {
      return false;
    }
    String transport = resolveTransport(deviceRecord);
    if (!TRANSPORT_REMOTE_PUSH.equals(transport)) {
      return false;
    }

    PushContent content = buildPushContent(record);
    try {
      if ("android".equals(deviceRecord.deviceType())) {
        return sendFcmNotification(deviceRecord, record, content);
      }
      if ("ios".equals(deviceRecord.deviceType())) {
        return sendApnsNotification(deviceRecord, record, content);
      }
    } catch (Exception error) {
      log.warn(
          "safe.notifications.remote_send_failed deviceUuid={} deviceType={} error={}",
          deviceRecord.deviceUuid(),
          deviceRecord.deviceType(),
          error.getMessage());
    }
    return false;
  }

  private String resolveTransport(DeviceRecord deviceRecord) {
    if (deviceRecord == null || deviceRecord.cloudMessagingToken().isBlank()) {
      return TRANSPORT_PULL_LOCAL;
    }
    if ("android".equals(deviceRecord.deviceType()) && isFcmConfigured()) {
      return TRANSPORT_REMOTE_PUSH;
    }
    if ("ios".equals(deviceRecord.deviceType()) && isApnsConfigured()) {
      return TRANSPORT_REMOTE_PUSH;
    }
    return TRANSPORT_PULL_LOCAL;
  }

  private boolean isFcmConfigured() {
    return fcmEnabled
        && !fcmProjectId.isBlank()
        && !fcmClientEmail.isBlank()
        && !fcmPrivateKeyPem.isBlank();
  }

  private String fcmConfigState() {
    if (!fcmEnabled) {
      return "disabled_by_flag";
    }
    if (fcmProjectId.isBlank()) {
      return "missing_project_id";
    }
    if (fcmClientEmail.isBlank()) {
      return "missing_client_email";
    }
    if (fcmPrivateKeyPem.isBlank()) {
      return "missing_private_key";
    }
    return "ready";
  }

  private boolean isApnsConfigured() {
    return apnsEnabled
        && !apnsTeamId.isBlank()
        && !apnsKeyId.isBlank()
        && !apnsBundleId.isBlank()
        && !apnsPrivateKeyPem.isBlank();
  }

  private String apnsConfigState() {
    if (!apnsEnabled) {
      return "disabled_by_flag";
    }
    if (apnsTeamId.isBlank()) {
      return "missing_team_id";
    }
    if (apnsKeyId.isBlank()) {
      return "missing_key_id";
    }
    if (apnsBundleId.isBlank()) {
      return "missing_bundle_id";
    }
    if (apnsPrivateKeyPem.isBlank()) {
      return "missing_private_key";
    }
    return "ready";
  }

  private PushContent buildPushContent(NotificationRecord record) {
    String safeShort = shortAddress(record.safeAddress(), 6, 4);
    String hashShort = shortAddress(record.safeTxHash(), 10, 6);
    String title =
        "CONFIRMATION_REQUEST".equals(record.notificationType())
            ? "Safe confirmation needed"
            : "Safe transaction ready";
    String body =
        "CONFIRMATION_REQUEST".equals(record.notificationType())
            ? "Safe " + safeShort + " · " + hashShort + " needs your confirmation ("
                + record.confirmationsSubmitted() + "/" + record.confirmationsRequired() + ")."
            : "Safe " + safeShort + " · " + hashShort + " reached threshold and is ready to execute.";
    return new PushContent(title, body, buildSafeDeepLink(record));
  }

  private boolean sendFcmNotification(
      DeviceRecord deviceRecord, NotificationRecord record, PushContent content) throws Exception {
    String accessToken = obtainFcmAccessToken();
    Map<String, Object> message =
        Map.of(
            "token", deviceRecord.cloudMessagingToken(),
            "data",
                Map.of(
                    "notificationId", record.id(),
                    "notificationType", record.notificationType(),
                    "safeDeepLink", content.deepLink(),
                    "title", content.title(),
                    "body", content.body()),
            "android", Map.of("priority", "HIGH"));
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create("https://fcm.googleapis.com/v1/projects/" + fcmProjectId + "/messages:send"))
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer " + accessToken)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of("message", message))))
            .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() / 100 == 2) {
      return true;
    }
    log.warn(
        "safe.notifications.fcm_failed deviceUuid={} status={} body={}",
        deviceRecord.deviceUuid(),
        response.statusCode(),
        truncate(response.body()));
    return false;
  }

  private boolean sendApnsNotification(
      DeviceRecord deviceRecord, NotificationRecord record, PushContent content) throws Exception {
    Map<String, Object> payload =
        Map.of(
            "aps",
                Map.of(
                    "alert", Map.of("title", content.title(), "body", content.body()),
                    "sound", "default"),
            "notificationId", record.id(),
            "notificationType", record.notificationType(),
            "safeDeepLink", content.deepLink());

    String host = apnsUseSandbox ? "https://api.sandbox.push.apple.com" : "https://api.push.apple.com";
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(host + "/3/device/" + deviceRecord.cloudMessagingToken()))
            .timeout(Duration.ofSeconds(15))
            .version(HttpClient.Version.HTTP_2)
            .header("authorization", "bearer " + obtainApnsJwt())
            .header("apns-topic", apnsBundleId)
            .header("apns-push-type", "alert")
            .header("apns-priority", "10")
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
            .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() / 100 == 2) {
      return true;
    }
    log.warn(
        "safe.notifications.apns_failed deviceUuid={} status={} body={}",
        deviceRecord.deviceUuid(),
        response.statusCode(),
        truncate(response.body()));
    return false;
  }

  private synchronized String obtainFcmAccessToken() throws Exception {
    Instant now = Instant.now();
    if (cachedFcmAccessToken != null && now.isBefore(cachedFcmAccessTokenExpiresAt.minusSeconds(60))) {
      return cachedFcmAccessToken;
    }

    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(fcmClientEmail)
            .subject(fcmClientEmail)
            .audience("https://oauth2.googleapis.com/token")
            .claim("scope", FCM_SCOPE)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plus(55, ChronoUnit.MINUTES)))
            .build();
    SignedJWT signedJwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).build(),
            claims);
    signedJwt.sign(new RSASSASigner(parseRsaPrivateKey(fcmPrivateKeyPem)));

    String formBody =
        "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
            + "&assertion=" + URLEncoder.encode(signedJwt.serialize(), StandardCharsets.UTF_8);
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create("https://oauth2.googleapis.com/token"))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(formBody))
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() / 100 != 2) {
      throw new IllegalStateException("FCM auth failed: " + response.statusCode());
    }

    Map<String, Object> body =
        objectMapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
    String accessToken = String.valueOf(body.getOrDefault("access_token", "")).trim();
    Number expiresIn = body.get("expires_in") instanceof Number ? (Number) body.get("expires_in") : 3600;
    if (accessToken.isBlank()) {
      throw new IllegalStateException("FCM auth returned empty access token");
    }
    cachedFcmAccessToken = accessToken;
    cachedFcmAccessTokenExpiresAt = now.plusSeconds(Math.max(60L, expiresIn.longValue()));
    return accessToken;
  }

  private synchronized String obtainApnsJwt() throws Exception {
    Instant now = Instant.now();
    if (cachedApnsJwt != null && now.isBefore(cachedApnsJwtExpiresAt.minusSeconds(60))) {
      return cachedApnsJwt;
    }

    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(apnsTeamId)
            .issueTime(Date.from(now))
            .build();
    SignedJWT signedJwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.ES256)
                .type(JOSEObjectType.JWT)
                .keyID(apnsKeyId)
                .build(),
            claims);
    signedJwt.sign(new ECDSASigner(parseEcPrivateKey(apnsPrivateKeyPem)));
    cachedApnsJwt = signedJwt.serialize();
    cachedApnsJwtExpiresAt = now.plus(50, ChronoUnit.MINUTES);
    return cachedApnsJwt;
  }

  private List<SubscriptionRecord> normalizeSubscriptions(List<SafeNotificationDtos.SafeSubscription> safes) {
    if (safes == null || safes.isEmpty()) {
      return List.of();
    }
    Map<String, SubscriptionRecord> bySafeId = new LinkedHashMap<>();
    for (SafeNotificationDtos.SafeSubscription safe : safes) {
      if (safe == null) {
        continue;
      }
      String safeAddress = normalizeAddress(safe.address());
      if (safe.chainId() <= 0 || safeAddress == null) {
        continue;
      }
      List<String> ownerAddresses = normalizeAddresses(safe.ownerAddresses());
      if (ownerAddresses.isEmpty()) {
        continue;
      }
      List<String> notificationTypes = normalizeNotificationTypes(safe.notificationTypes());
      SubscriptionRecord normalized =
          new SubscriptionRecord(safe.chainId(), safeAddress, ownerAddresses, notificationTypes);
      bySafeId.put(safeId(normalized.chainId(), normalized.safeAddress()), normalized);
    }
    return List.copyOf(bySafeId.values());
  }

  private static List<String> normalizeNotificationTypes(List<String> notificationTypes) {
    if (notificationTypes == null || notificationTypes.isEmpty()) {
      return DEFAULT_NOTIFICATION_TYPES;
    }
    LinkedHashSet<String> next = new LinkedHashSet<>();
    for (String raw : notificationTypes) {
      String value = String.valueOf(raw == null ? "" : raw).trim().toUpperCase(Locale.ROOT);
      if (DEFAULT_NOTIFICATION_TYPES.contains(value)) {
        next.add(value);
      }
    }
    return next.isEmpty() ? DEFAULT_NOTIFICATION_TYPES : List.copyOf(next);
  }

  private static List<String> normalizeAddresses(List<String> addresses) {
    if (addresses == null || addresses.isEmpty()) {
      return List.of();
    }
    LinkedHashSet<String> next = new LinkedHashSet<>();
    for (String raw : addresses) {
      String normalized = normalizeAddress(raw);
      if (normalized != null) {
        next.add(normalized);
      }
    }
    return List.copyOf(next);
  }

  private static String resolveDeviceUuid(String requestedDeviceUuid, String fallbackDeviceUuid) {
    String deviceUuid = normalizeDeviceUuid(requestedDeviceUuid);
    if (deviceUuid != null) {
      return deviceUuid;
    }
    deviceUuid = normalizeDeviceUuid(fallbackDeviceUuid);
    if (deviceUuid != null) {
      return deviceUuid;
    }
    throw new ResponseStatusException(BAD_REQUEST, "Missing device uuid");
  }

  private static String normalizeDeviceUuid(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String normalizeDeviceType(String value) {
    String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    if ("ios".equals(normalized) || "android".equals(normalized)) {
      return normalized;
    }
    return "android";
  }

  private static String normalizeToken(String value) {
    if (value == null) {
      return "";
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? "" : trimmed;
  }

  private static String normalizePem(String value) {
    String normalized = normalizeToken(value);
    if (normalized.isEmpty()) {
      return "";
    }
    return normalized.replace("\\n", "\n").replace("\r\n", "\n").replace('\r', '\n');
  }

  private static String normalizeAddress(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    if (!EVM_ADDRESS_PATTERN.matcher(trimmed).matches()) {
      return null;
    }
    return trimmed.toLowerCase(Locale.ROOT);
  }

  private static String notificationTypeForAction(String action) {
    if ("needs_confirmation".equals(action)) {
      return "CONFIRMATION_REQUEST";
    }
    if ("ready_to_execute".equals(action)) {
      return "READY_TO_EXECUTE";
    }
    return null;
  }

  private static String safeId(int chainId, String safeAddress) {
    return chainId + ":" + safeAddress.toLowerCase(Locale.ROOT);
  }

  private static String shortAddress(String value, int head, int tail) {
    String normalized = normalizeToken(value);
    if (normalized.isEmpty()) {
      return "--";
    }
    if (normalized.length() <= Math.max(0, head) + Math.max(0, tail) + 3) {
      return normalized;
    }
    return normalized.substring(0, Math.max(0, head))
        + "..."
        + normalized.substring(normalized.length() - Math.max(0, tail));
  }

  private static String truncate(String value) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.length() <= 300) {
      return normalized;
    }
    return normalized.substring(0, 300) + "...";
  }

  private static RSAPrivateKey parseRsaPrivateKey(String pem) throws Exception {
    byte[] encoded = decodePem(pem);
    return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded));
  }

  private static ECPrivateKey parseEcPrivateKey(String pem) throws Exception {
    byte[] encoded = decodePem(pem);
    return (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(encoded));
  }

  private static byte[] decodePem(String pem) {
    String normalized = normalizePem(pem);
    String sanitized =
        normalized
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s+", "");
    if (sanitized.isBlank()) {
      throw new IllegalArgumentException("Missing private key content");
    }
    return Base64.getDecoder().decode(sanitized);
  }

  private static String buildSafeDeepLink(NotificationRecord record) {
    String base = "veilwallet://safe/" + record.chainId() + "/" + record.safeAddress();
    String safeTxHash = normalizeToken(record.safeTxHash());
    return safeTxHash.isEmpty() ? base : base + "/tx/" + safeTxHash;
  }

  private static String stateKey(int chainId, String safeTxHash) {
    return chainId + ":" + (safeTxHash == null ? "" : safeTxHash.toLowerCase(Locale.ROOT));
  }

  private static String deviceKey(String deviceUuid) {
    return "safe:notif:device:" + deviceUuid;
  }

  private static String deviceSubscriptionsKey(String deviceUuid) {
    return deviceKey(deviceUuid) + ":safes";
  }

  private static String deviceSubscriptionKey(String deviceUuid, String safeId) {
    return deviceKey(deviceUuid) + ":sub:" + safeId;
  }

  private static String deviceQueueKey(String deviceUuid) {
    return deviceKey(deviceUuid) + ":queue";
  }

  private static String deviceStateKey(String deviceUuid) {
    return deviceKey(deviceUuid) + ":state";
  }

  private static String safeDevicesKey(String safeId) {
    return "safe:notif:safe:" + safeId + ":devices";
  }

  private Set<String> safeSetMembers(String key) {
    try {
      Set<String> values = redis.opsForSet().members(key);
      return values == null ? Collections.emptySet() : values;
    } catch (Exception ignored) {
      return Collections.emptySet();
    }
  }

  private <T> void setJson(String key, T value, long ttlSeconds) {
    if (key == null || key.isBlank() || value == null) {
      return;
    }
    try {
      redis.opsForValue().set(key, objectMapper.writeValueAsString(value), Duration.ofSeconds(Math.max(1, ttlSeconds)));
    } catch (Exception ignored) {
      // ignore serialization failures
    }
  }

  private <T> T readJson(String key, Class<T> type) {
    try {
      String value = redis.opsForValue().get(key);
      if (value == null || value.isBlank()) {
        return null;
      }
      return objectMapper.readValue(value, type);
    } catch (Exception ignored) {
      return null;
    }
  }

  private <T> T readJsonString(String value, Class<T> type) {
    try {
      if (value == null || value.isBlank()) {
        return null;
      }
      return objectMapper.readValue(value, type);
    } catch (Exception ignored) {
      return null;
    }
  }

  private record PushContent(String title, String body, String deepLink) {}

  private record DeviceRecord(
      String deviceUuid, String deviceType, String cloudMessagingToken, String updatedAt) {}

  private record SubscriptionRecord(
      int chainId, String safeAddress, List<String> ownerAddresses, List<String> notificationTypes) {}

  private record NotificationRecord(
      String id,
      String notificationType,
      int chainId,
      String safeAddress,
      String safeTxHash,
      int confirmationsSubmitted,
      int confirmationsRequired,
      String createdAt) {}
}
