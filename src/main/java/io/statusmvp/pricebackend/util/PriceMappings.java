package io.statusmvp.pricebackend.util;

import java.util.Map;
import java.util.Set;

public final class PriceMappings {
  private PriceMappings() {}

  public static final Set<String> STABLECOINS =
      Set.of(
          "USDC",
          "USDT",
          "DAI",
          "BUSD",
          // Common stables (extend as needed)
          "TUSD",
          "USDP",
          "GUSD",
          "FRAX",
          "LUSD",
          "SUSD",
          "USDD",
          "USDG",
          "PYUSD",
          "FDUSD",
          "USDE");

  // Minimal symbol -> CoinGecko "id" mapping (extend as needed).
  public static final Map<String, String> COINGECKO_IDS =
      Map.ofEntries(
          Map.entry("ETH", "ethereum"),
          Map.entry("WETH", "weth"),
          Map.entry("BTC", "bitcoin"),
          Map.entry("WBTC", "wrapped-bitcoin"),
          Map.entry("LTC", "litecoin"),
          Map.entry("BCH", "bitcoin-cash"),
          Map.entry("DOGE", "dogecoin"),
          Map.entry("XRP", "ripple"),
          Map.entry("ADA", "cardano"),
          Map.entry("TRX", "tron"),
          Map.entry("XLM", "stellar"),
          Map.entry("ETC", "ethereum-classic"),
          Map.entry("ATOM", "cosmos"),
          Map.entry("NEAR", "near"),
          Map.entry("APT", "aptos"),
          Map.entry("BNB", "binancecoin"),
          Map.entry("MATIC", "matic-network"),
          Map.entry("POL", "polygon-ecosystem-token"),
          Map.entry("FTM", "fantom"),
          Map.entry("SUI", "sui"),
          Map.entry("USDC", "usd-coin"),
          Map.entry("USDT", "tether"),
          Map.entry("DAI", "dai"),
          Map.entry("FRAX", "frax"),
          Map.entry("LUSD", "liquity-usd"),
          Map.entry("GUSD", "gemini-dollar"),
          Map.entry("PYUSD", "paypal-usd"),
          Map.entry("ARB", "arbitrum"),
          Map.entry("OP", "optimism"),
          Map.entry("AVAX", "avalanche-2"),
          Map.entry("DOT", "polkadot"),
          Map.entry("SOL", "solana"),
          Map.entry("LINK", "chainlink"),
          Map.entry("UNI", "uniswap"),
          Map.entry("AAVE", "aave"),
          Map.entry("MKR", "maker"),
          Map.entry("SNX", "synthetix-network-token"),
          Map.entry("CRV", "curve-dao-token"),
          Map.entry("CVX", "convex-finance"),
          Map.entry("LDO", "lido-dao"),
          Map.entry("RPL", "rocket-pool"),
          Map.entry("COMP", "compound-governance-token"),
          Map.entry("SUSHI", "sushi"),
          Map.entry("BAL", "balancer"),
          Map.entry("GRT", "the-graph"),
          Map.entry("IMX", "immutable-x"),
          Map.entry("RNDR", "render-token"),
          Map.entry("INJ", "injective-protocol"),
          Map.entry("TIA", "celestia"),
          Map.entry("SEI", "sei-network"),
          Map.entry("APE", "apecoin"),
          Map.entry("PEPE", "pepe"),
          Map.entry("SHIB", "shiba-inu"),
          Map.entry("FLOKI", "floki"),
          Map.entry("BONK", "bonk"));

  // ChainId -> CoinGecko platform id (for /simple/token_price/{platform})
  public static final Map<Integer, String> COINGECKO_PLATFORMS =
      Map.ofEntries(
          Map.entry(1, "ethereum"),
          Map.entry(10, "optimistic-ethereum"),
          Map.entry(56, "binance-smart-chain"),
          Map.entry(137, "polygon-pos"),
          Map.entry(42161, "arbitrum-one"),
          Map.entry(8453, "base"),
          Map.entry(43114, "avalanche"));
}


