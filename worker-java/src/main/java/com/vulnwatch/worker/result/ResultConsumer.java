package com.vulnwatch.worker.result;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vulnwatch.worker.ai.AiEnricher;
import com.vulnwatch.worker.models.AggregatedScanData;
import com.vulnwatch.worker.repository.FindingRepository;
import com.vulnwatch.worker.retry.RetryHandler;
import com.vulnwatch.worker.state.RedisSurfaceStateManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ResultConsumer {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AiEnricher aiEnricher;
    private final FindingRepository findingRepository;
    private final RedisSurfaceStateManager stateManager;
    private final RetryHandler retryHandler;

    @Value("${redis.stream.surface-results:surface:result:stream}")
    private String streamKey;

    @Value("${redis.stream.surface-results.group:surface-result-group}")
    private String groupName;

    @Value("${redis.stream.surface-results.consumer:result-worker-1}")
    private String consumerName;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @PostConstruct
    public void start() {

        createConsumerGroup();

        executor.submit(this::consumeLoop);

        log.info(
                "ResultConsumer started | stream={} group={} consumer={}",
                streamKey,
                groupName,
                consumerName
        );
    }

    private void createConsumerGroup() {

        try {

            redisTemplate.opsForStream().createGroup(
                    streamKey,
                    ReadOffset.latest(),
                    groupName
            );

            log.info("Created Redis consumer group {}", groupName);

        } catch (Exception e) {

            log.info("Consumer group already exists: {}", groupName);
        }
    }

    private void consumeLoop() {

        while (true) {

            try {
                @SuppressWarnings("unchecked")
                List<MapRecord<String, Object, Object>> messages =
                        redisTemplate.opsForStream().read(
                                Consumer.from(groupName, consumerName),
                                StreamReadOptions.empty()
                                        .count(10)
                                        .block(Duration.ofSeconds(5)),
                                StreamOffset.create(
                                        streamKey,
                                        ReadOffset.lastConsumed()
                                )
                        );

                if (messages == null || messages.isEmpty()) {
                    continue;
                }

                for (MapRecord<String, Object, Object> message : messages) {

                    processMessage(message);
                }

            } catch (Exception e) {

                log.error("ResultConsumer loop failure", e);

                sleep();
            }
        }
    }

    private void processMessage(
            MapRecord<String, Object, Object> message
    ) {

        RecordId messageId = message.getId();

        try {

            Map<Object, Object> body = message.getValue();

            String scanId = (String) body.get("scanId");
            String surface = (String) body.get("surface");
            String rawDataJson = (String) body.get("rawData");

            log.info(
                    "Received result event | scanId={} surface={} messageId={}",
                    scanId,
                    surface,
                    messageId
            );

            /*
             * 1. Update surface state
             *
             * scan:{scanId}:surfaces
             *
             * DNS -> SUCCESS
             * SSL -> SUCCESS
             */
            redisTemplate.opsForHash().put(
                    "scan:" + scanId + ":surfaces",
                    surface,
                    "SUCCESS"
            );

            /*
             * 2. Deserialize raw data
             */
            Map<String, Object> rawData =
                    objectMapper.readValue(
                            rawDataJson,
                            new TypeReference<>() {}
                    );



            /*
             * 3. Run AI enrichment
             */
            var findings = aiEnricher.enrich()
            /*
             * 4. Persist findings
             *
             * Replace with DB persistence later
             */

            findingRepository.save();
            stateManager.updateSuccess();


            /*
             * 5. ACK message
             */
            redisTemplate.opsForStream().acknowledge(
                    streamKey,
                    groupName,
                    messageId
            );

            log.info(
                    "Processed and ACKed result event | scanId={} surface={}",
                    scanId,
                    surface
            );

        } catch (Exception e) {

            log.error(
                    "Failed processing result message {}",
                    messageId,
                    e
            );
            stateManager.updateFailure();
            retryHandler.handleFailure();

        }
    }

    private void sleep() {

        try {
            Thread.sleep(3000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}