package com.vulnwatch.worker.result;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vulnwatch.worker.CompletionEvent;
import com.vulnwatch.worker.SurfaceResultEvent;
import com.vulnwatch.worker.ai.AiEnricher;
import com.vulnwatch.worker.config.RedisConfig;
import com.vulnwatch.worker.enums.SurfaceType;
import com.vulnwatch.worker.models.AggregatedScanData;
import com.vulnwatch.worker.queue.SurfaceEventPublisher;
import com.vulnwatch.worker.repository.FindingRepository;
import com.vulnwatch.worker.retry.DeadLetterQueueHandler;
import com.vulnwatch.worker.retry.RetryHandler;
import com.vulnwatch.worker.state.RedisSurfaceStateManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ResultConsumer {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private final AiEnricher aiEnricher;
    private final FindingRepository findingRepository;
    private final RedisSurfaceStateManager stateManager;
    private final RetryHandler retryHandler;
    private final DeadLetterQueueHandler deadLetterQueueHandler;


    @Value("${redis.stream.surface-results.group:surface-result-group}")
    private String GROUP_NAME;

    @Value("${redis.stream.surface-results.consumer:result-worker-1}")
    private String CONSUMER_NAME;

    private final String streamKey = RedisConfig.Keys.SURFACE_RESULT_STREAM;
    private final String scanResult = RedisConfig.Keys.SCAN_RESULTS_LIST;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @PostConstruct
    public void start() {

        createConsumerGroup();

        executor.submit(this::consumeLoop);

        log.info(
                "ResultConsumer started | stream={} group={} consumer={}",
                RedisConfig.Keys.SCAN_RESULTS_LIST,
                GROUP_NAME,
                CONSUMER_NAME
        );
    }

    private void createConsumerGroup() {

        try {

            redisTemplate.opsForStream().createGroup(
                    RedisConfig.Keys.SCAN_RESULTS_LIST,
                    ReadOffset.latest(),
                    GROUP_NAME
            );

            log.info("Created Redis consumer group {}", GROUP_NAME);

        } catch (Exception e) {

            log.info("Consumer group already exists: {}", GROUP_NAME);
        }
    }

    private void consumeLoop() {

        while (true) {

            try {
                @SuppressWarnings("unchecked")
                List<MapRecord<String, Object, Object>> messages =
                        redisTemplate.opsForStream().read(
                                Consumer.from(GROUP_NAME, CONSUMER_NAME),
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

                    endCheck(message);

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

        SurfaceResultEvent event = extractEvent(message);

        try {

            log.info(
                    "Received result event | scanId={} surface={} messageId={}",
                    event.getScanId(),
                    event.getSurface(),
                    messageId
            );

            if (!event.isSuccess()){
                stateManager.updateFailure(event.getScanId(), event.getSurface(), event.getErrorMessage());
                retryHandler.handleFailure(event);
                return;
            }

            // Insert findings into the AI Enricher here and persist
            var findings = aiEnricher.enrich()
            findingRepository.save();



            redisTemplate.opsForStream().acknowledge(
                    streamKey,
                    GROUP_NAME,
                    messageId
            );
            stateManager.updateSuccess(event.getScanId(), event.getSurface());


            log.info(
                    "Processed and ACKed result event | scanId={} surface={}",
                    event.getScanId(),
                    event.getSurface()
            );

        } catch (Exception e) {

            if (event != null) {
                stateManager.updateFailure(event.getScanId(), event.getSurface(), e.getMessage());
                retryHandler.handleFailure(event);

                log.error(
                        "Failed processing result message {}",
                        messageId,
                        e
                );

            } else {
                log.error(
                        "Invalid payload in message {}",
                        messageId,
                        e
                );
            }
        }
    }

    private void endCheck(MapRecord<String, Object, Object> message){

        RecordId messageId = message.getId();

        SurfaceResultEvent event = extractEvent(message);

        boolean isTerminal = stateManager.isAllTerminal(event.getScanId());

        if(isTerminal){
            CompletionEvent completionEvent = CompletionEvent.completed(event.getScanId(), )
            redisTemplate.opsForList()
                    .leftPush(scanResult, );

        }

    }

    private SurfaceResultEvent extractEvent(MapRecord<String, Object, Object> message){
        RecordId messageId = message.getId();

        SurfaceResultEvent event = null;

        try {
            Map<Object, Object> body = message.getValue();

            event = objectMapper.convertValue(body, SurfaceResultEvent.class);

            return event;
        } catch (Exception e) {
            log.error(
                    "Invalid payload in message {}",
                    messageId,
                    e
            );
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