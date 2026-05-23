package com.vulnwatch.worker.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    /**
     * ChatClient backed by whichever model is enabled via:
     *   spring.ai.openai.chat.enabled / spring.ai.anthropic.chat.enabled / spring.ai.google.genai.chat.enabled
     *
     * Only one ChatModel bean will exist at runtime.
     * Switch models by changing worker.ai.provider and the corresponding .chat.enabled flags.
     */
    @Bean
    public ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }
}
