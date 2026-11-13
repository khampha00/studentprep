package com.studentprep.student;

import com.studentprep.student.StudentCreatedEvent;
import com.studentprep.student.StudentDeletedEvent;
import com.studentprep.student.StudentPinResetEvent;
import com.studentprep.student.StudentUpdatedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.security.SecureRandom;
import java.util.Random;

@Service
public class StudentManagementService {

    private final StudentRepository studentRepository;
    private final ApplicationEventPublisher eventPublisher;

    public StudentManagementService(StudentRepository studentRepository, ApplicationEventPublisher eventPublisher) {
        this.studentRepository = studentRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public Student createStudent(Student student) {
        if (student.getId() == null) {
            student.setId(UUID.randomUUID());
        }
        if (student.getPin() == null) {
            student.setPin(generatePin());
        }
        if (student.getRegistrationNumber() == null) {
            String regNum;
            do {
                regNum = generateRegistrationNumber();
            } while (studentRepository.existsByRegistrationNumber(regNum));
            student.setRegistrationNumber(regNum);
        }

        Student saved = studentRepository.save(student);
        eventPublisher.publishEvent(new StudentCreatedEvent(saved.getId(), saved.getRegistrationNumber(), saved.getPin()));
        return saved;
    }

    @Transactional
    public Student updateStudent(UUID id, Student updatedData) {
        Student existing = studentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Student not found"));

        existing.setName(updatedData.getName());
        existing.setExamCenter(updatedData.getExamCenter());
        if (updatedData.getSubjects() != null) {
            existing.setSubjects(updatedData.getSubjects());
        }
        
        Student saved = studentRepository.save(existing);
        eventPublisher.publishEvent(new StudentUpdatedEvent(saved.getId(), saved.getRegistrationNumber(), saved.getPin()));
        return saved;
    }

    @Transactional
    public void deleteStudent(UUID id) {
        if (studentRepository.existsById(id)) {
            studentRepository.deleteById(id);
            eventPublisher.publishEvent(new StudentDeletedEvent(id));
        }
    }

    @Transactional
    public String resetPin(UUID id) {
        Student student = studentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Student not found"));
        
        String newPin = generatePin();
        student.setPin(newPin);
        studentRepository.save(student);
        
        eventPublisher.publishEvent(new StudentPinResetEvent(id, newPin));
        return newPin;
    }
    
    private String generatePin() {
        SecureRandom secureRandom = new SecureRandom();
        return String.valueOf(10000 + secureRandom.nextInt(90000));
    }

    private String generateRegistrationNumber() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder("JAMB-2026-");
        Random random = new Random();
        for (int i = 0; i < 6; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
