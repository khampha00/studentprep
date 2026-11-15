package com.studentprep.exam;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

public record ExamSubmittedEvent(
        UUID sessionId,
        UUID userId,
        Map<String, Object> finalState,
        ExamSessionStatus terminalStatus,
        Map<String, Object> snapshotPayload
) {
    public ExamSubmittedEvent(UUID sessionId, UUID userId, Map<String, Object> finalState) {
        this(sessionId, userId, finalState, ExamSessionStatus.SUBMITTED, Collections.emptyMap());
    }
}
