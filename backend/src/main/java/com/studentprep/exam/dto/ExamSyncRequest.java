package com.studentprep.exam.dto;

import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record ExamSyncRequest(
        @NotNull(message = "statePayload is required")
        Map<String, Object> statePayload
) {}
