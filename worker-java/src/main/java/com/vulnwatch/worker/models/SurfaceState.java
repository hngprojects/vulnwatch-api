package com.vulnwatch.worker.models;

import com.vulnwatch.worker.enums.SurfaceStatus;
import com.vulnwatch.worker.enums.SurfaceType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Immutable snapshot of a single surface's scan state.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SurfaceState {

    private SurfaceType surfaceType;

    private SurfaceStatus status;

    private int retryCount;

    private String lastError;

    private Instant completedAt;

    private Instant lastAttemptAt;



    public boolean isTerminal() {
        return status != null && status.isTerminal();
    }

    public boolean isPending() {
        return status != null && status.isPending();
    }

    public boolean isRetrying() {
        return status != null && status.isRetrying();
    }

    public boolean isSuccess() {
        return status != null && status.isSuccess();
    }

    public boolean isFailed() {
        return status != null && status.isFailed();
    }
}