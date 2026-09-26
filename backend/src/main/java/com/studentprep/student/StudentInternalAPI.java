package com.studentprep.student;

import java.util.Optional;
import java.util.UUID;

public interface StudentInternalAPI {
    Optional<Student> findById(UUID id);
    Optional<Student> findByRegistrationNumberIgnoreCase(String registrationNumber);
    long countStudents();
    java.util.List<java.util.Map<String, Object>> getSubjectRegistrations();
}
