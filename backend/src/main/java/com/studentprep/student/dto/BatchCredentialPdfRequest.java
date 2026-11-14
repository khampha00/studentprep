package com.studentprep.student.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request payload for exporting a consolidated credential PDF for a specific cohort of candidates.
 */
public record BatchCredentialPdfRequest(
        @NotEmpty(message = "At least one registration number is required")
        @Size(min = 1, max = 5000, message = "At most 5000 registration numbers may be exported at once")
        List<@NotBlank(message = "Registration number must not be blank") String> registrationNumbers) {
}
