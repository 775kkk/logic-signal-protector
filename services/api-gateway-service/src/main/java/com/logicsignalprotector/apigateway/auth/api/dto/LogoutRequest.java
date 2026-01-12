package com.logicsignalprotector.apigateway.auth.api.dto;

import jakarta.validation.constraints.NotBlank;

public record LogoutRequest(@NotBlank String refreshToken) {}
