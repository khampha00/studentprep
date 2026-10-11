package com.studentprep.student;

import java.util.UUID;

public record StudentCreatedEvent(UUID studentId, String registrationNumber, String pin) {}
