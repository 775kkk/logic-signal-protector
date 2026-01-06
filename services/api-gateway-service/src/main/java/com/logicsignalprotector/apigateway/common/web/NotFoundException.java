package com.logicsignalprotector.apigateway.common.web;

/** 404 with a stable JSON payload via ApiExceptionHandler. */
public class NotFoundException extends RuntimeException {
  public NotFoundException(String message) {
    super(message);
  }
}
