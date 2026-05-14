package com.vulnwatch.worker.exception;

public class AiServiceException extends RuntimeException {
  public AiServiceException(String message) {
    super(message);
  }

  public AiServiceException(String message, Throwable e) {
    super(message, e);
  }
}
