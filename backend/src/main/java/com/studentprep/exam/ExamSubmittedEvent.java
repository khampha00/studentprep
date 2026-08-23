package com.studentprep.exam;
import java.util.Map;
import java.util.UUID;
public record ExamSubmittedEvent(UUID sessionId, UUID userId, Map<String, Object> finalState) {}
