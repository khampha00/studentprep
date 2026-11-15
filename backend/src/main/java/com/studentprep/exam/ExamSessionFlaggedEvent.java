package com.studentprep.exam;

import java.util.UUID;

public record ExamSessionFlaggedEvent(
        UUID sessionId,
        UUID userId,
        String reason
) {}
