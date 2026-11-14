package com.studentprep.security.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank(message = "Identifier is required") String identifier,
        @NotBlank(message = "PIN is required") String pin) {
}
