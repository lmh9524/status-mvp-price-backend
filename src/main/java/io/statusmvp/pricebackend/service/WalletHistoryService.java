package io.statusmvp.pricebackend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.statusmvp.pricebackend.model.wallethistory.WalletHistoryDtos;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@Service
public class WalletHistoryService {
  private static final Logger log = LoggerFactory.getLogger(WalletHistoryService.class);
  private static final Pattern EVM_ADDRESS = Pattern.compile("^0x[0-9a-fA-F]{40}$");
  private static final int DEFAULT_LIMIT = 100;
  private static final int MAX_LIMIT = 100;
  private static final String SOURCE = "wallet-history-backend";

  private static final Map<Integer, String> SUPPORTED_CHAINS =
      Map.of(
          1, "eth",
          10, "optimism",
          56, "bsc",
          137, "polygon",
          196, "xlayer",
          8453, "base",
          42161, "arbitrum");

  private final WebClient webClient;
  private final ObjectMapper mapper;
  private final String ankrBaseUrl;
  private final String ankrApiKey;
  private final Duration timeout;

  public WalletHistoryService(
      WebClient webClient,
      ObjectMapper mapper,
      @Value("${app.portfolio.ankrBaseUrl:https://rpc.ankr.com/multichain}") String ankrBaseUrl,
      @Value("${app.portfolio.ankrApiKey:}") String ankrApiKey,
      @Value("${app.portfolio.timeoutMs:12000}") long timeoutMs) {
    this.webClient = webClient;
    this.mapper = mapper;
    this.ankrBaseUrl = normalizeBaseUrl(ankrBaseUrl);
    this.ankrApiKey = ankrApiKey == null ? "" : ankrApiKey.trim();
    this.timeout = Duration.ofMillis(Math.max(1000L, timeoutMs));
  }

  public WalletHistoryDtos.QueryResponse query(WalletHistoryDtos.QueryRequest request) {
    RequestSpec spec = validate(request);
    CursorState cursorState = decodeCursor(spec.cursor(), spec.tokenAddress());
    CursorState nextState = new CursorState(spec.tokenAddress(), new HashMap<>());
    List<WalletHistoryDtos.HistoryItem> items = new ArrayList<>();
    boolean partial = false;

    if (ankrBaseUrl.isBlank() || ankrApiKey.isBlank()) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "ANKR upstream is not configured");
    }

    for (int chainId : spec.chainIds()) {
      String blockchain = SUPPORTED_CHAINS.get(chainId);
      ChainCursor existingCursor = cursorState.chain(String.valueOf(chainId));
      ChainCursor workingCursor = existingCursor == null ? new ChainCursor(null, false, null, false) : existingCursor;

      if (!spec.tokenOnly() && !workingCursor.nativeDone()) {
        try {
          PageResult nativePage =
              fetchAnkrPage(
                  "ankr_getTransactionsByAddress",
                  blockchain,
                  spec.address(),
                  null,
                  spec.limit(),
                  workingCursor.nativePageToken());
          items.addAll(mapNativeItems(chainId, nativePage.items()));
          workingCursor =
              workingCursor.withNative(
                  nativePage.nextPageToken(), nativePage.nextPageToken() == null);
        } catch (Exception err) {
          partial = true;
          log.warn("[wallet-history] native history fetch failed chainId={} error={}", chainId, err.toString());
        }
      }

      if (!workingCursor.tokenDone()) {
        try {
          PageResult tokenPage =
              fetchAnkrPage(
                  "ankr_getTokenTransfers",
                  blockchain,
                  spec.address(),
                  spec.tokenAddress(),
                  spec.limit(),
                  workingCursor.tokenPageToken());
          items.addAll(mapTokenTransferItems(chainId, tokenPage.items(), spec.tokenAddress()));
          workingCursor =
              workingCursor.withToken(tokenPage.nextPageToken(), tokenPage.nextPageToken() == null);
        } catch (Exception err) {
          partial = true;
          log.warn("[wallet-history] token history fetch failed chainId={} error={}", chainId, err.toString());
        }
      }

      nextState.chains().put(String.valueOf(chainId), workingCursor);
    }

    List<WalletHistoryDtos.HistoryItem> deduped =
        dedupe(items).stream().sorted((a, b) -> Long.compare(b.createdAt(), a.createdAt())).toList();
    boolean hasMore = hasOpenSource(nextState, spec);
    String nextCursor = hasMore ? encodeCursor(nextState) : null;
    return new WalletHistoryDtos.QueryResponse(
        deduped, nextCursor, hasMore, partial, List.of("ankr"));
  }

  private RequestSpec validate(WalletHistoryDtos.QueryRequest request) {
    if (request == null) {
      throw badRequest("request body is required");
    }
    String address = normalizeAddress(request.address());
    if (address == null) {
      throw badRequest("address must be a valid EVM address");
    }
    if (request.chainIds() == null || request.chainIds().isEmpty()) {
      throw badRequest("chainIds must not be empty");
    }
    List<Integer> chainIds =
        request.chainIds().stream()
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    if (chainIds.isEmpty()) {
      throw badRequest("chainIds must not be empty");
    }
    for (Integer chainId : chainIds) {
      if (!SUPPORTED_CHAINS.containsKey(chainId)) {
        throw badRequest("unsupported chainId");
      }
    }
    int limit = request.limit() == null ? DEFAULT_LIMIT : request.limit();
    if (limit < 1 || limit > MAX_LIMIT) {
      throw badRequest("limit is out of range");
    }
    String tokenAddress = null;
    if (request.tokenAddress() != null && !request.tokenAddress().isBlank()) {
      tokenAddress = normalizeAddress(request.tokenAddress());
      if (tokenAddress == null) {
        throw badRequest("tokenAddress must be a valid EVM address");
      }
    }
    return new RequestSpec(address, chainIds, blankToNull(request.cursor()), limit, tokenAddress);
  }

  private ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private PageResult fetchAnkrPage(
      String method,
      String blockchain,
      String ownerAddress,
      String tokenAddress,
      int limit,
      String pageToken) {
    ObjectNode body = mapper.createObjectNode();
    body.put("jsonrpc", "2.0");
    body.put("id", 1);
    body.put("method", method);
    ObjectNode params = mapper.createObjectNode();
    ArrayNode blockchains = params.putArray("blockchain");
    blockchains.add(blockchain);
    if ("ankr_getTransactionsByAddress".equals(method)) {
      params.put("address", ownerAddress);
      params.put("descOrder", true);
    } else {
      params.put("address", ownerAddress);
      params.put("fromTimestamp", 0);
      params.put("toTimestamp", System.currentTimeMillis() / 1000L);
      params.put("descOrder", true);
      if (tokenAddress != null) {
        params.put("contractAddress", tokenAddress);
      }
    }
    params.put("pageSize", limit);
    if (pageToken != null) {
      params.put("pageToken", pageToken);
    }
    body.set("params", params);

    JsonNode response =
        webClient
            .post()
            .uri(URI.create(ankrBaseUrl + "/" + ankrApiKey))
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .bodyValue(body)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .timeout(timeout)
            .onErrorMap(
                TimeoutException.class,
                err -> new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "ANKR upstream timeout", err))
            .block();

    if (response == null) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "ANKR upstream returned empty response");
    }
    if (response.hasNonNull("error")) {
      String message = response.path("error").path("message").asText("ANKR upstream error");
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, message);
    }
    JsonNode result = response.path("result");
    JsonNode items =
        "ankr_getTransactionsByAddress".equals(method)
            ? result.path("transactions")
            : result.path("transfers");
    String next =
        blankToNull(result.path("nextPageToken").asText(null));
    if (next == null) {
      next = blankToNull(result.path("pageToken").asText(null));
    }
    return new PageResult(items.isArray() ? items : mapper.createArrayNode(), next);
  }

  private List<WalletHistoryDtos.HistoryItem> mapNativeItems(int chainId, JsonNode txs) {
    List<WalletHistoryDtos.HistoryItem> out = new ArrayList<>();
    for (JsonNode tx : txs) {
      String hash = blankToNull(tx.path("hash").asText(null));
      String from = normalizeAddress(tx.path("from").asText(null));
      String to = normalizeAddress(tx.path("to").asText(null));
      BigInteger rawValue = parseBigInt(tx.path("value"));
      Long timestamp = parseTimestampMs(tx.path("timestamp"));
      String input = tx.path("input").asText("").trim().toLowerCase(Locale.ROOT);
      if (hash == null || from == null || to == null || rawValue == null || timestamp == null) continue;
      if (rawValue.compareTo(BigInteger.ZERO) <= 0) continue;
      if (!input.isBlank() && !"0x".equals(input)) continue;
      String normalizedHash = hash.trim();
      out.add(
          new WalletHistoryDtos.HistoryItem(
              "ext:" + chainId + ":native:" + normalizedHash.toLowerCase(Locale.ROOT),
              chainId,
              normalizedHash,
              from,
              to,
              formatUnits(rawValue, 18),
              parseEvmStatus(tx.path("status")),
              timestamp,
              "transfer",
              null,
              null,
              null,
              true,
              SOURCE));
    }
    return out;
  }

  private List<WalletHistoryDtos.HistoryItem> mapTokenTransferItems(
      int chainId, JsonNode transfers, String targetTokenAddress) {
    List<WalletHistoryDtos.HistoryItem> out = new ArrayList<>();
    for (JsonNode transfer : transfers) {
      String hash = blankToNull(transfer.path("transactionHash").asText(null));
      String contract = normalizeAddress(transfer.path("contractAddress").asText(null));
      String from = normalizeAddress(transfer.path("fromAddress").asText(null));
      String to = normalizeAddress(transfer.path("toAddress").asText(null));
      Integer decimals = parseInteger(transfer.path("tokenDecimals"));
      BigInteger rawValue = pickTransferRawValue(transfer);
      Long timestamp = parseTimestampMs(transfer.path("timestamp"));
      if (hash == null || contract == null || from == null || to == null) continue;
      if (targetTokenAddress != null && !contract.equalsIgnoreCase(targetTokenAddress)) continue;
      if (decimals == null || rawValue == null || timestamp == null) continue;
      String symbol = blankToNull(transfer.path("tokenSymbol").asText(null));
      String normalizedHash = hash.trim();
      out.add(
          new WalletHistoryDtos.HistoryItem(
              "ext:"
                  + chainId
                  + ":token:"
                  + normalizedHash.toLowerCase(Locale.ROOT)
                  + ":"
                  + contract.toLowerCase(Locale.ROOT)
                  + ":"
                  + from.toLowerCase(Locale.ROOT)
                  + ":"
                  + to.toLowerCase(Locale.ROOT)
                  + ":"
                  + rawValue
                  + ":"
                  + timestamp,
              chainId,
              normalizedHash,
              from,
              to,
              formatUnits(rawValue, decimals),
              "confirmed",
              timestamp,
              "transfer",
              contract,
              symbol,
              decimals,
              true,
              SOURCE));
    }
    return out;
  }

  private List<WalletHistoryDtos.HistoryItem> dedupe(List<WalletHistoryDtos.HistoryItem> items) {
    Map<String, WalletHistoryDtos.HistoryItem> byKey = new LinkedHashMap<>();
    for (WalletHistoryDtos.HistoryItem item : items) {
      String key =
          item.chainId()
              + ":"
              + lower(item.hash())
              + ":"
              + lower(item.tokenAddress() == null ? "__native__" : item.tokenAddress())
              + ":"
              + lower(item.from())
              + ":"
              + lower(item.to())
              + ":"
              + item.value()
              + ":"
              + item.createdAt();
      byKey.putIfAbsent(key, item);
    }
    return new ArrayList<>(byKey.values());
  }

  private boolean hasOpenSource(CursorState state, RequestSpec spec) {
    for (int chainId : spec.chainIds()) {
      ChainCursor cursor = state.chain(String.valueOf(chainId));
      if (cursor == null) return true;
      if (!spec.tokenOnly() && !cursor.nativeDone()) return true;
      if (!cursor.tokenDone()) return true;
    }
    return false;
  }

  private CursorState decodeCursor(String rawCursor, String tokenAddress) {
    if (rawCursor == null) return new CursorState(tokenAddress, new HashMap<>());
    try {
      byte[] decoded = Base64.getUrlDecoder().decode(rawCursor);
      CursorState state = mapper.readValue(decoded, CursorState.class);
      String cursorToken = normalizeNullableAddress(state.tokenAddress());
      if (!Objects.equals(cursorToken, tokenAddress)) {
        return new CursorState(tokenAddress, new HashMap<>());
      }
      return new CursorState(tokenAddress, new HashMap<>(state.chains()));
    } catch (Exception err) {
      throw badRequest("cursor is invalid");
    }
  }

  private String encodeCursor(CursorState state) {
    try {
      byte[] json = mapper.writeValueAsBytes(state);
      return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
    } catch (JsonProcessingException err) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "failed to encode cursor", err);
    }
  }

  private static String normalizeBaseUrl(String value) {
    return value == null ? "" : value.trim().replaceAll("/+$", "");
  }

  private static String normalizeAddress(String value) {
    String trimmed = value == null ? "" : value.trim();
    return EVM_ADDRESS.matcher(trimmed).matches() ? trimmed : null;
  }

  private static String normalizeNullableAddress(String value) {
    if (value == null || value.isBlank()) return null;
    return normalizeAddress(value);
  }

  private static String blankToNull(String value) {
    if (value == null) return null;
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String lower(String value) {
    return value == null ? "" : value.toLowerCase(Locale.ROOT);
  }

  private static BigInteger parseBigInt(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) return null;
    if (node.isNumber()) return node.bigIntegerValue();
    String value = node.asText("").trim();
    if (value.isEmpty()) return null;
    try {
      if (value.startsWith("0x") || value.startsWith("0X")) {
        return new BigInteger(value.substring(2), 16);
      }
      return new BigInteger(value);
    } catch (NumberFormatException err) {
      return null;
    }
  }

  private static BigInteger pickTransferRawValue(JsonNode transfer) {
    for (String field : List.of("valueRawInteger", "balanceRawInteger", "value", "tokenBalance")) {
      BigInteger parsed = parseBigInt(transfer.path(field));
      if (parsed != null) return parsed;
    }
    return null;
  }

  private static Integer parseInteger(JsonNode node) {
    BigInteger parsed = parseBigInt(node);
    if (parsed == null) return null;
    if (parsed.compareTo(BigInteger.ZERO) < 0 || parsed.compareTo(BigInteger.valueOf(255)) > 0) {
      return null;
    }
    return parsed.intValue();
  }

  private static Long parseTimestampMs(JsonNode node) {
    BigInteger parsed = parseBigInt(node);
    if (parsed == null) return null;
    long value;
    try {
      value = parsed.longValueExact();
    } catch (ArithmeticException err) {
      return null;
    }
    return value > 10_000_000_000L ? value : value * 1000L;
  }

  private static String parseEvmStatus(JsonNode node) {
    BigInteger parsed = parseBigInt(node);
    if (parsed == null) return "confirmed";
    return BigInteger.ONE.equals(parsed) ? "confirmed" : "failed";
  }

  private static String formatUnits(BigInteger value, int decimals) {
    BigDecimal decimal = new BigDecimal(value).movePointLeft(decimals).stripTrailingZeros();
    if (decimal.compareTo(BigDecimal.ZERO) == 0) return "0";
    return decimal.toPlainString();
  }

  private record RequestSpec(
      String address, List<Integer> chainIds, String cursor, int limit, String tokenAddress) {
    boolean tokenOnly() {
      return tokenAddress != null;
    }
  }

  private record PageResult(JsonNode items, String nextPageToken) {}

  private record CursorState(String tokenAddress, Map<String, ChainCursor> chains) {
    CursorState {
      chains = chains == null ? new HashMap<>() : chains;
    }

    ChainCursor chain(String chainId) {
      return chains.get(chainId);
    }
  }

  private record ChainCursor(
      String nativePageToken, boolean nativeDone, String tokenPageToken, boolean tokenDone) {
    ChainCursor withNative(String pageToken, boolean done) {
      return new ChainCursor(pageToken, done, tokenPageToken, tokenDone);
    }

    ChainCursor withToken(String pageToken, boolean done) {
      return new ChainCursor(nativePageToken, nativeDone, pageToken, done);
    }
  }
}
