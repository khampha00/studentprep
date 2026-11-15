package com.studentprep.exam;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface ExamInternalAPI {
    boolean hasFlaggedSession(UUID studentId);
    boolean hasActiveSession(UUID studentId);
    List<Map<String, Object>> getLiveSessions();
    long countCompletedExamsBySessionId(UUID examId);
    List<Map<String, Object>> getFlaggedOrLateSessions();

    void clearLateSession(UUID sessionId, String actor);
    default void clearLateSession(UUID sessionId) {
        clearLateSession(sessionId, "ADMIN");
    }

    void voidSession(UUID sessionId, String actor);
    default void voidSession(UUID sessionId) {
        voidSession(sessionId, "ADMIN");
    }

    void grantRetake(UUID studentId, String actor);
    default void grantRetake(UUID studentId) {
        grantRetake(studentId, "ADMIN");
    }

    default String getSessionAnswerReceiptCode(UUID sessionId) {
        return null;
    }
}
