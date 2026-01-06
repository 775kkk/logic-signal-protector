package com.logicsignalprotector.apigateway.common.web;

/** Step 1.3: semantic HTTP 409 for resource conflicts. */
public class ConflictException extends RuntimeException {
  public ConflictException(String message) {
    super(message);
  }
}
