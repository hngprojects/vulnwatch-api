package com.vulnwatch.worker.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI Configuration for Spring AI integration. Configures ChatClient for OpenAI API communication.
 */
@Configuration
public class AiConfig {

  @Value("${spring.ai.openai.api-key}")
  private String apiKey;

  @Value("${spring.ai.openai.model:gpt-4}")
  private String model;

  @Value("${spring.ai.openai.temperature:0.2}")
  private double temperature;

  @Value("${spring.ai.openai.max-tokens:2000}")
  private int maxTokens;

  /**
   * Creates the OpenAiApi bean with API key using builder pattern.
   *
   * @return Configured OpenAiApi instance
   */
  @Bean
  public OpenAiApi openAiApi() {
    return OpenAiApi.builder().apiKey(apiKey).build();
  }

  /**
   * Creates the OpenAiChatModel bean with configuration.
   *
   * @param openAiApi The configured OpenAiApi instance
   * @return Configured OpenAiChatModel instance
   */
  @Bean
  public OpenAiChatModel openAiChatModel(OpenAiApi openAiApi) {
    OpenAiChatOptions options =
        OpenAiChatOptions.builder()
            .model(model)
            .temperature(temperature)
            .maxTokens(maxTokens)
            .build();

    return OpenAiChatModel.builder().openAiApi(openAiApi).defaultOptions(options).build();
  }

  /**
   * Creates the ChatClient bean for making AI calls.
   *
   * @param chatModel The configured OpenAiChatModel
   * @return ChatClient instance
   */
  @Bean
  public ChatClient chatClient(OpenAiChatModel chatModel) {
    return ChatClient.builder(chatModel).build();
  }
}
