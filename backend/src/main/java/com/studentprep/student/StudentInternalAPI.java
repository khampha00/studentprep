package com.studentprep.student;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface StudentInternalAPI {
    Optional<Student> findById(UUID id);
    Optional<Student> findByRegistrationNumberIgnoreCase(String registrationNumber);
    long countStudents();
    List<Map<String, Object>> getSubjectRegistrations();
}
