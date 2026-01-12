package com.logicsignalprotector.apigateway.auth.api.dto;

public record TokenResponse(String accessToken, String tokenType, long expiresInSeconds) {}
