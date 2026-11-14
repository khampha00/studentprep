package com.studentprep.student.dto;

import java.util.List;
import java.util.UUID;

public record StudentResponse(
        UUID id,
        String name,
        String registrationNumber,
        String examCenter,
        boolean pinResetRequired,
        boolean hasActiveSession,
        List<SubjectSummaryResponse> subjects) {
}
