package com.studentprep.exam.dto;

import java.util.List;
import java.util.UUID;

public class ExamPayloadResponse {
    private UUID examId;
    private Long shuffleSeed;
    private Integer durationMinutes;
    private List<Object> questions;
    private java.util.Map<String, String> contexts;

    public UUID getExamId() { return examId; }
    public void setExamId(UUID examId) { this.examId = examId; }
    public Long getShuffleSeed() { return shuffleSeed; }
    public void setShuffleSeed(Long shuffleSeed) { this.shuffleSeed = shuffleSeed; }
    public Integer getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(Integer durationMinutes) { this.durationMinutes = durationMinutes; }
    public List<Object> getQuestions() { return questions; }
    public void setQuestions(List<Object> questions) { this.questions = questions; }
    public java.util.Map<String, String> getContexts() { return contexts; }
    public void setContexts(java.util.Map<String, String> contexts) { this.contexts = contexts; }
}
