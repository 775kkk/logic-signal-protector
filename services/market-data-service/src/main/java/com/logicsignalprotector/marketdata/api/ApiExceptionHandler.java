package com.logicsignalprotector.marketdata.api;

import com.logicsignalprotector.marketdata.client.MoexClientException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(MoexClientException.class)
  public ResponseEntity<Map<String, Object>> handleMoexClientException(MoexClientException ex) {
    log.error("MOEX ISS error", ex);
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
        .body(Map.of("error", "MOEX_ISS_ERROR", "message", ex.getMessage()));
  }
}
