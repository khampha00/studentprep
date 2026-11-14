package com.studentprep.student;

import java.util.UUID;

public record StudentUpdatedEvent(UUID studentId, String registrationNumber) {
}
