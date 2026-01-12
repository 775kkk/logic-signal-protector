package com.logicsignalprotector.apigateway.auth.api.dto;

public record TokensResponse(
    String accessToken,
    String tokenType,
    long accessExpiresInSeconds,
    String refreshToken,
    long refreshExpiresInSeconds) {}
