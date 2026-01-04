package com.logicsignalprotector.apigateway.auth.service;

public class AuthUnauthorizedException extends RuntimeException {
  public AuthUnauthorizedException(String message) {
    super(message);
  }
}
