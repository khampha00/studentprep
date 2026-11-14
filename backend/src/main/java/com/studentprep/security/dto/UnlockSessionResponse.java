package com.studentprep.security.dto;

import java.util.UUID;

public record UnlockSessionResponse(
        UUID studentId,
        String registrationNumber,
        boolean unlocked,
        String message) {
}
