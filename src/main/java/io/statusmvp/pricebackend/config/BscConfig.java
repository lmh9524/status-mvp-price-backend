package io.statusmvp.pricebackend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

@Configuration
public class BscConfig {

  @Bean
  @ConditionalOnProperty(name = "app.bsc.rpcUrl")
  public Web3j bscWeb3j(@Value("${app.bsc.rpcUrl}") String rpcUrl) {
    return Web3j.build(new HttpService(rpcUrl));
  }
}


