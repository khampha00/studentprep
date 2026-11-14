package com.studentprep.student.dto;

import java.util.List;

public record BulkImportResponse(
        int created,
        List<String> errors,
        List<BulkCredentialResponse> credentials) {
}
