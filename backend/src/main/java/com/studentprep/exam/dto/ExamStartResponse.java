package com.studentprep.exam.dto;
import java.util.Map;
import java.util.UUID;
public record ExamStartResponse(UUID sessionId, int shuffleSeed, Map<String, Object> payload) {}
