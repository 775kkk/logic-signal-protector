package com.logicsignalprotector.apigateway.common.web;

import com.logicsignalprotector.apigateway.auth.service.AuthUnauthorizedException;
import com.logicsignalprotector.apigateway.common.ratelimit.TooManyRequestsException;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class ApiExceptionHandler {

  @ExceptionHandler(TooManyRequestsException.class)
  public ResponseEntity<ErrorResponse> handle429(TooManyRequestsException e) {
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .body(ErrorResponse.of("TOO_MANY_REQUESTS", e.getMessage()));
  }

  @ExceptionHandler(AuthUnauthorizedException.class)
  public ResponseEntity<ErrorResponse> handle401(AuthUnauthorizedException e) {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .body(ErrorResponse.of("UNAUTHORIZED", e.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
    return ResponseEntity.badRequest()
        .body(ErrorResponse.of("VALIDATION_ERROR", "Invalid request"));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
    return ResponseEntity.badRequest().body(ErrorResponse.of("BAD_REQUEST", e.getMessage()));
  }

  @ExceptionHandler(ConflictException.class)
  public ResponseEntity<ErrorResponse> handleConflict(ConflictException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(ErrorResponse.of("CONFLICT", e.getMessage()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handle500(Exception e) {
    log.error("Unhandled exception", e);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(ErrorResponse.of("INTERNAL_ERROR", "Unexpected error"));
  }

  public record ErrorResponse(String code, String message, Instant timestamp) {
    public static ErrorResponse of(String code, String message) {
      return new ErrorResponse(code, message, Instant.now());
    }
  }
}
