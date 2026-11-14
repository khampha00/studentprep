package com.studentprep.student;

import java.util.UUID;

public record StudentPinResetEvent(
        UUID studentId,
        String registrationNumber,
        String newPinHash,
        boolean pinResetRequired) {
}
