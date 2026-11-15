package com.studentprep.exam;

import java.time.Instant;
import java.util.UUID;

public record ExamModerationUpdatedEvent(
        UUID sessionId,
        UUID studentId,
        String action,
        String actor,
        Instant occurredAt
) {}
