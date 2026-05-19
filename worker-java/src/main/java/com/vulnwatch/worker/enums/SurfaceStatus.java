package com.vulnwatch.worker.enums;

/**
 * Represents the lifecycle state of a single scanner surface within a scan job.
 * Replaces magic strings throughout the codebase.
 */
public enum SurfaceStatus {
    PENDING,
    RETRYING,
    FAILED,
    SUCCESS,
    PERMANENTLY_FAILED;


    public boolean isTerminal() {
        return this == SUCCESS || this == PERMANENTLY_FAILED;
    }

    public boolean isPending() {
        return this == PENDING;
    }

    public boolean isRetrying() {
        return this == RETRYING;
    }

    public boolean isSuccess() {
        return this == SUCCESS;
    }

    public boolean isFailed() {
        return this == FAILED || this == PERMANENTLY_FAILED;
    }
}
