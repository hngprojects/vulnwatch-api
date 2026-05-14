package com.vulnwatch.worker.ai;

import com.vulnwatch.worker.exception.AiServiceException;
import com.vulnwatch.worker.models.ai.AiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * Responsible for calling OpenAI API and parsing the response. Uses Spring AI's ChatClient with
 * automatic JSON schema enforcement.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiResponseParser {

  private final ChatClient chatClient;

  /**
   * Calls OpenAI API with the given prompt and returns structured response. Spring AI automatically
   * enforces the JSON schema from AiResponse class.
   *
   * @param prompt The formatted prompt with role instruction and scan data
   * @return Parsed AiResponse object containing security score and findings
   * @throws AiServiceException if the API call fails or response is invalid
   */
  public AiResponse callOpenAi(String prompt) {
    log.debug("Calling OpenAI API with prompt length: {}", prompt.length());

    try {
      AiResponse response = chatClient.prompt().user(prompt).call().entity(AiResponse.class);

      assert response != null;
      log.debug(
          "OpenAI response received - score: {}, findings: {}",
          response.getSecurityScore(),
          response.getFindings() != null ? response.getFindings().size() : 0);

      return response;

    } catch (Exception e) {
      log.error("OpenAI API call failed: {}", e.getMessage(), e);
      throw new AiServiceException("Failed to get response from OpenAI", e);
    }
  }
}
