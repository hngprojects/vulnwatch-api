package com.vulnwatch.worker.config;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class AsyncConfig {

  @Primary
  @Bean
  public Executor taskExecutor() {
    return Executors.newFixedThreadPool(10);
  }
}
