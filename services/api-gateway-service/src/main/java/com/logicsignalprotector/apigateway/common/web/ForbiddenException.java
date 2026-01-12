package com.logicsignalprotector.apigateway.common.web;

/** 403 with a stable JSON payload via ApiExceptionHandler. */
public class ForbiddenException extends RuntimeException {
  public ForbiddenException(String message) {
    super(message);
  }
}
