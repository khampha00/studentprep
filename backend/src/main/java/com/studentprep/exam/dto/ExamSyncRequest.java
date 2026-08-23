package com.studentprep.exam.dto;
import java.util.Map;
public record ExamSyncRequest(Map<String, Object> statePayload) {}
