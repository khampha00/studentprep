package com.studentprep.exam;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface ExamInternalAPI {
    boolean hasFlaggedSession(UUID studentId);
    boolean hasActiveSession(UUID studentId);
    List<Map<String, Object>> getLiveSessions();
    long countCompletedExamsBySessionId(UUID examId);
}
