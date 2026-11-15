package com.studentprep.questionbank.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.studentprep.questionbank.QuestionStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * Validated request payload for updating an existing question's lifecycle status, topic, and JSONB content.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QuestionUpdateRequest(
        @NotNull(message = "Question status is required")
        QuestionStatus status,
        @Size(max = 255, message = "Topic must not exceed 255 characters")
        String topic,
        @NotNull(message = "Question content is required")
        Map<String, Object> content) {
}
