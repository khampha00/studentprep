package com.studentprep.exam.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ExamStartResponse(
        UUID sessionId,
        int shuffleSeed,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        String expectedEndTime,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        String serverTime,
        Map<String, Object> payload,
        Map<String, Object> statePayload,
        boolean resumed
) {
    public ExamStartResponse(
            UUID sessionId,
            int shuffleSeed,
            Instant expectedEndTime,
            Instant serverTime,
            Map<String, Object> payload,
            Map<String, Object> statePayload,
            boolean resumed
    ) {
        this(
                sessionId,
                shuffleSeed,
                expectedEndTime != null ? expectedEndTime.toString() : null,
                serverTime != null ? serverTime.toString() : null,
                payload,
                statePayload,
                resumed
        );
    }

    public ExamStartResponse(
            UUID sessionId,
            int shuffleSeed,
            Map<String, Object> payload,
            Map<String, Object> statePayload,
            boolean resumed
    ) {
        this(sessionId, shuffleSeed, (String) null, (String) null, payload, statePayload, resumed);
    }

    public ExamStartResponse(UUID sessionId, int shuffleSeed, Map<String, Object> payload) {
        this(sessionId, shuffleSeed, (String) null, (String) null, payload, null, false);
    }
}
