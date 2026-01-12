package com.logicsignalprotector.marketdata.client;

public class MoexClientException extends RuntimeException {
  public MoexClientException(String message) {
    super(message);
  }

  public MoexClientException(String message, Throwable cause) {
    super(message, cause);
  }
}
