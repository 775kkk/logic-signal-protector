package com.logicsignalprotector.apigateway.auth.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
    @NotBlank @Size(min = 3, max = 64) String login,
    @NotBlank @Size(min = 8, max = 128) String password) {}
