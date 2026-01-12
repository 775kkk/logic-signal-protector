package com.logicsignalprotector.apigateway.common.ratelimit;

public class TooManyRequestsException extends RuntimeException {
  public TooManyRequestsException(String message) {
    super(message);
  }
}
