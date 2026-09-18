package com.studentprep.exam.dto;

import java.util.Map;
import java.util.UUID;

public record ExamStartResponse(
    UUID sessionId,
    int shuffleSeed,
    Map<String, Object> payload,
    Map<String, Object> statePayload,
    boolean resumed
) {
    public ExamStartResponse(UUID sessionId, int shuffleSeed, Map<String, Object> payload) {
        this(sessionId, shuffleSeed, payload, null, false);
    }
}
