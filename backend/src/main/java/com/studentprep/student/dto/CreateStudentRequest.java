package com.studentprep.student.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Set;
import java.util.UUID;

public record CreateStudentRequest(
        @NotBlank(message = "Student name is required")
        @Size(max = 255, message = "Student name must not exceed 255 characters")
        String name,
        @Size(max = 255, message = "Exam center must not exceed 255 characters")
        String examCenter,
        Set<UUID> subjectIds) {
}
