package com.vulnwatch.worker.config;

import java.time.Clock;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class AsyncConfig {

  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  @Primary
  @Bean
  public Executor taskExecutor() {
    return Executors.newFixedThreadPool(15);
  }
}
