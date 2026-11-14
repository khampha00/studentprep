package com.studentprep.student.dto;

public record BulkCredentialResponse(
        String name,
        String registrationNumber,
        String oneTimePin) {
}
