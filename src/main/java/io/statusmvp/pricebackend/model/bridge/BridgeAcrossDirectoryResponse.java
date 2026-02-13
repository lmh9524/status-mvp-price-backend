package io.statusmvp.pricebackend.model.bridge;

import java.util.List;

public record BridgeAcrossDirectoryResponse(
    long updatedAt,
    Allowlist allowlist,
    List<Chain> chains,
    List<Route> routes) {

  public record Allowlist(List<Long> chainIds, List<String> tokenSymbols) {}

  public record Chain(
      long chainId,
      String name,
      String publicRpcUrl,
      String explorerUrl,
      String logoUrl,
      String spokePool,
      Long spokePoolBlock,
      List<Token> inputTokens,
      List<Token> outputTokens) {}

  public record Token(String address, String symbol, String name, int decimals, String logoUrl) {}

  public record Route(
      boolean isNative,
      long originChainId,
      long destinationChainId,
      String inputToken,
      String outputToken,
      String inputTokenSymbol,
      String outputTokenSymbol) {}
}
