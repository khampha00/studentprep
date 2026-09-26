package com.studentprep.student;

import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
public class StudentInternalAPIImpl implements StudentInternalAPI {

    private final StudentRepository studentRepository;

    public StudentInternalAPIImpl(StudentRepository studentRepository) {
        this.studentRepository = studentRepository;
    }

    @Override
    public Optional<Student> findById(UUID id) {
        return studentRepository.findById(id);
    }

    @Override
    public Optional<Student> findByRegistrationNumberIgnoreCase(String registrationNumber) {
        return studentRepository.findByRegistrationNumberIgnoreCase(registrationNumber);
    }

    @Override
    public long countStudents() {
        return studentRepository.count();
    }

    @Override
    public java.util.List<java.util.Map<String, Object>> getSubjectRegistrations() {
        return studentRepository.countSubjectRegistrations().stream().map(obj -> {
            java.util.Map<String, Object> map = new java.util.HashMap<>();
            map.put("subjectName", obj[0]);
            map.put("enrollmentCount", obj[1]);
            return map;
        }).collect(java.util.stream.Collectors.toList());
    }
}
