package com.vulnwatch.worker.event;

import com.vulnwatch.worker.enums.SurfaceType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Event published to Redis Stream when a scanner completes (success or failure).
 *
 * <p>This is consumed by {@code ResultConsumer} which triggers AI enrichment per surface.
 *
 * <p><b>Idempotency:</b> Each event carries a unique {@code eventId}. The consumer
 * uses this to detect and discard duplicate events, preventing double AI enrichment.
 *
 * <p><b>Raw data offload:</b> Large raw data can be stored separately in Redis Hash,
 * with only a reference key {@code rawDataKey} carried in the event.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class SurfaceResultEvent {

    /** Unique identifier for this event (idempotency) */
    @Builder.Default
    private UUID eventId = UUID.randomUUID();

    /** ID of the scan this result belongs to */
    private UUID scanId;

    /** Which surface scanner produced this result (DNS, SSL, HTTP_HEADERS, etc.) */
    private SurfaceType surface;

    /** Whether the scanner completed successfully
     * -- GETTER --
     *  Returns true if this event represents a successful scanner result.
     */
    private boolean success;

    /** Raw technical data from the scanner (only present if success=true) */
    private Map<String, Object> rawData;

    /** Reference key to raw data stored in Redis Hash (if offloaded) */
    private String rawDataKey;

    /** Error message (only present if success=false) */
    private String errorMessage;

    /** Which retry attempt this is (0 = first attempt) */
    private int attempt;

    /** How long the scanner took to execute (milliseconds) */
    private Long durationMs;

    /** When the event was created */
    @Builder.Default
    private Instant timestamp = Instant.now();


    /**
     * Creates a success event with raw data.
     */
    public static SurfaceResultEvent success(UUID scanId, SurfaceType surface,
                                             Map<String, Object> rawData, int attempt) {
        return SurfaceResultEvent.builder()
                .scanId(scanId)
                .surface(surface)
                .success(true)
                .rawData(rawData != null ? rawData : new HashMap<>())
                .attempt(attempt)
                .build();
    }

    /**
     * Creates a success event with raw data and duration.
     */
    public static SurfaceResultEvent success(UUID scanId, SurfaceType surface,
                                             Map<String, Object> rawData, int attempt, long durationMs) {
        return SurfaceResultEvent.builder()
                .scanId(scanId)
                .surface(surface)
                .success(true)
                .rawData(rawData != null ? rawData : new HashMap<>())
                .attempt(attempt)
                .durationMs(durationMs)
                .build();
    }

    /**
     * Creates a success event with only a reference key to offloaded raw data.
     */
    public static SurfaceResultEvent successWithKey(UUID scanId, SurfaceType surface,
                                                    String rawDataKey, int attempt) {
        return SurfaceResultEvent.builder()
                .scanId(scanId)
                .surface(surface)
                .success(true)
                .rawDataKey(rawDataKey)
                .attempt(attempt)
                .build();
    }

    /**
     * Creates a failure event.
     */
    public static SurfaceResultEvent failure(UUID scanId, SurfaceType surface,
                                             String errorMessage, int attempt) {
        return SurfaceResultEvent.builder()
                .scanId(scanId)
                .surface(surface)
                .success(false)
                .errorMessage(errorMessage != null ? errorMessage : "Unknown error")
                .attempt(attempt)
                .build();
    }

    /**
     * Creates a failure event with duration (for timeout cases).
     */
    public static SurfaceResultEvent failure(UUID scanId, SurfaceType surface,
                                             String errorMessage, int attempt, long durationMs) {
        return SurfaceResultEvent.builder()
                .scanId(scanId)
                .surface(surface)
                .success(false)
                .errorMessage(errorMessage != null ? errorMessage : "Unknown error")
                .attempt(attempt)
                .durationMs(durationMs)
                .build();
    }

    /**
     * Creates a scan-level failure event (no specific surface).
     * Used when the scan fails before any scanner runs (e.g., no eligible scanners).
     */
    public static SurfaceResultEvent scanFailed(UUID scanId, String reason) {
        return SurfaceResultEvent.builder()
                .scanId(scanId)
                .surface(null)
                .success(false)
                .errorMessage(reason != null ? reason : "Scan startup failed")
                .attempt(0)
                .build();
    }


    /**
     * Returns true if this event represents a failure.
     */
    public boolean isFailure() {
        return !success;
    }

    /**
     * Returns true if raw data is present in the event (not offloaded).
     */
    public boolean hasRawData() {
        return rawData != null && !rawData.isEmpty();
    }

    /**
     * Returns true if raw data is offloaded to a separate Redis key.
     */
    public boolean hasRawDataKey() {
        return rawDataKey != null && !rawDataKey.isBlank();
    }

    /**
     * Creates a lightweight copy of this event without raw data.
     * Useful for logging or when only metadata is needed.
     */
    public SurfaceResultEvent withoutRawData() {
        return this.toBuilder()
                .rawData(null)
                .build();
    }

    /**
     * Creates a retry event based on this failure event.
     * Increments the attempt counter and preserves the error message.
     */
    public SurfaceResultEvent forRetry(int newAttempt) {
        return SurfaceResultEvent.builder()
                .scanId(scanId)
                .surface(surface)
                .success(false)
                .errorMessage(errorMessage)
                .attempt(newAttempt)
                .build();
    }
}