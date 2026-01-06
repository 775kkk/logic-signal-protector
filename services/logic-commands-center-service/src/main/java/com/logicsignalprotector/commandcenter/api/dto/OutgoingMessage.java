package com.logicsignalprotector.commandcenter.api.dto;

import jakarta.validation.constraints.NotBlank;

public record OutgoingMessage(@NotBlank String text) {}
