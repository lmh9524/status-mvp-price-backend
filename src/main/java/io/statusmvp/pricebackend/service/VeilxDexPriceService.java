package io.statusmvp.pricebackend.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;

/**
 * VEILX price source (BSC mainnet) via PancakeSwap V2 Router getAmountsOut().
 *
 * <p>Returns "1 VEILX ~ X USDT" and treats USDT~USD for MVP.
 *
 * <p>Enable by setting {@code BSC_RPC_URL} and {@code VEILX_CONTRACT_ADDRESS}.
 */
@Component
public class VeilxDexPriceService {
  private static final Logger log = LoggerFactory.getLogger(VeilxDexPriceService.class);

  // PancakeSwap V2 router on BSC mainnet
  private static final String DEFAULT_PANCAKE_ROUTER_V2 = "0x10ED43C718714eb63d5aA57B78B54704E256024E";
  // USDT (BSC) commonly used address
  private static final String DEFAULT_BSC_USDT = "0x55d398326f99059fF775485246999027B3197955";

  private final Optional<Web3j> web3j;
  private final String routerAddress;
  private final String veilxAddress;
  private final String usdtAddress;

  // Cache decimals to avoid extra calls
  private final AtomicReference<Integer> veilxDecimals = new AtomicReference<>(null);
  private final AtomicReference<Integer> usdtDecimals = new AtomicReference<>(null);

  public VeilxDexPriceService(
      ObjectProvider<Web3j> bscWeb3jProvider,
      @Value("${app.dex.pancake.routerAddress:" + DEFAULT_PANCAKE_ROUTER_V2 + "}") String routerAddress,
      @Value("${app.dex.veilx.address:}") String veilxAddress,
      @Value("${app.dex.usdt.address:" + DEFAULT_BSC_USDT + "}") String usdtAddress) {
    this.web3j = Optional.ofNullable(bscWeb3jProvider.getIfAvailable());
    this.routerAddress = normalizeAddress(routerAddress);
    this.veilxAddress = normalizeAddress(veilxAddress);
    this.usdtAddress = normalizeAddress(usdtAddress);
  }

  public boolean isEnabled() {
    return web3j.isPresent() && !veilxAddress.isBlank() && !routerAddress.isBlank() && !usdtAddress.isBlank();
  }

  /** Returns the configured VEILX contract address (lowercased), or empty string if not set. */
  public String veilxContractLower() {
    return veilxAddress == null ? "" : veilxAddress.trim().toLowerCase();
  }

  /** Returns VEILX price in USD (via USDT), e.g. 0.0042. */
  public Optional<Double> fetchVeilxUsdPrice() {
    if (!isEnabled()) return Optional.empty();
    try {
      int vDec = getDecimalsCached(veilxAddress, veilxDecimals, 18);
      int uDec = getDecimalsCached(usdtAddress, usdtDecimals, 18);

      BigInteger amountIn = BigInteger.TEN.pow(vDec); // 1 VEILX
      Function fn =
          new Function(
              "getAmountsOut",
              Arrays.asList(
                  new Uint256(amountIn),
                  new DynamicArray<>(Address.class, new Address(veilxAddress), new Address(usdtAddress))),
              Collections.singletonList(new TypeReference<DynamicArray<Uint256>>() {}));

      String data = FunctionEncoder.encode(fn);
      Transaction tx = Transaction.createEthCallTransaction(null, routerAddress, data);
      EthCall resp = web3j.get().ethCall(tx, DefaultBlockParameterName.LATEST).send();
      if (resp.hasError()) {
        log.warn("VEILX DEX price eth_call error: {}", resp.getError().getMessage());
        return Optional.empty();
      }

      @SuppressWarnings("rawtypes")
      List<Type> decoded = FunctionReturnDecoder.decode(resp.getValue(), fn.getOutputParameters());
      if (decoded.isEmpty()) return Optional.empty();
      @SuppressWarnings("unchecked")
      List<Uint256> amounts = ((DynamicArray<Uint256>) decoded.get(0)).getValue();
      if (amounts.size() < 2) return Optional.empty();

      BigInteger outRaw = amounts.get(amounts.size() - 1).getValue();
      if (outRaw.signum() <= 0) return Optional.empty();

      BigDecimal outUsdt = new BigDecimal(outRaw).movePointLeft(uDec);
      BigDecimal price = outUsdt.setScale(8, RoundingMode.HALF_UP);
      return Optional.of(price.doubleValue());
    } catch (Exception e) {
      log.warn("VEILX DEX price request failed", e);
      return Optional.empty();
    }
  }

  private int getDecimalsCached(String token, AtomicReference<Integer> cache, int fallback) {
    Integer cached = cache.get();
    if (cached != null && cached > 0) return cached;
    int dec = fetchDecimals(token).orElse(fallback);
    cache.compareAndSet(null, dec);
    return dec;
  }

  private Optional<Integer> fetchDecimals(String tokenAddress) {
    if (web3j.isEmpty()) return Optional.empty();
    try {
      Function fn = new Function("decimals", List.of(), Collections.singletonList(new TypeReference<Uint8>() {}));
      String data = FunctionEncoder.encode(fn);
      Transaction tx = Transaction.createEthCallTransaction(null, tokenAddress, data);
      EthCall resp = web3j.get().ethCall(tx, DefaultBlockParameterName.LATEST).send();
      if (resp.hasError()) return Optional.empty();
      @SuppressWarnings("rawtypes")
      List<Type> decoded = FunctionReturnDecoder.decode(resp.getValue(), fn.getOutputParameters());
      if (decoded.isEmpty()) return Optional.empty();
      Uint8 v = (Uint8) decoded.get(0);
      return Optional.of(v.getValue().intValue());
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  private static String normalizeAddress(String addr) {
    if (addr == null) return "";
    String a = addr.trim();
    return a.isEmpty() ? "" : a;
  }
}


