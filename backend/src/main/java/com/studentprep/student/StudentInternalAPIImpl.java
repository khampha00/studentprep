package com.studentprep.student;

import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

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
    public List<Map<String, Object>> getSubjectRegistrations() {
        return studentRepository.countSubjectRegistrations().stream().map(obj -> {
            Map<String, Object> map = new HashMap<>();
            map.put("subjectName", obj[0]);
            map.put("enrollmentCount", obj[1]);
            return map;
        }).collect(Collectors.toList());
    }
}
