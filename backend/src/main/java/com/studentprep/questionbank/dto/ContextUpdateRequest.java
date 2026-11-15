package com.studentprep.questionbank.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Validated request payload for updating a shared {@code QuestionContext}'s passage text and attached media assets.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ContextUpdateRequest(
        String passage,
        List<Map<String, Object>> assets) {
}
