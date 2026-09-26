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
}
