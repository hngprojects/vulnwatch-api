package com.vulnwatch.worker;

import com.vulnwatch.worker.enums.SurfaceType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class CompletionEvent {
    /** Unique identifier for this event (idempotency) */
    @Builder.Default
    private UUID eventId = UUID.randomUUID();

    /** ID of the scan this result belongs to */
    private UUID scanId;

    /** Which surface scanner produced this result (DNS, SSL, HTTP_HEADERS, etc.) */
    private boolean success;

    /**
     * Aggregate Security Score
     */
    private int score;

    /** When the event was created */
    @Builder.Default
    private Instant timestamp = Instant.now();

    /**
     * Creates a success completion event.
     */
    public static CompletionEvent completed(UUID scanId, int score){
        return CompletionEvent.builder()
                .scanId(scanId)
                .score(score)
                .build();
    }
}
