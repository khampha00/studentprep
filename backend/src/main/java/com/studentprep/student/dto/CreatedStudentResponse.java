package com.studentprep.student.dto;

import java.util.List;
import java.util.UUID;

public record CreatedStudentResponse(
        UUID id,
        String name,
        String registrationNumber,
        List<SubjectSummaryResponse> subjects,
        String oneTimePin) {
}
