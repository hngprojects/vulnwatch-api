package com.vulnwatch.worker.exception;

/** Thrown when a completion message cannot be serialized or pushed to Redis. */
public class ScanPublishException extends RuntimeException {
    public ScanPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
