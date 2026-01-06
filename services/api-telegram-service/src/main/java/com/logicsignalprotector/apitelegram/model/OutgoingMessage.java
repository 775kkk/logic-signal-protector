package com.logicsignalprotector.apitelegram.model;

import jakarta.validation.constraints.NotBlank;

public record OutgoingMessage(@NotBlank String text) {}
