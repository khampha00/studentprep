package com.studentprep.student;

import com.studentprep.common.SessionRedisKeys;
import com.studentprep.questionbank.Subject;
import com.studentprep.questionbank.SubjectRepository;
import com.studentprep.student.dto.CreateStudentRequest;
import com.studentprep.student.dto.ResetPinResponse;
import com.studentprep.student.dto.UpdateStudentRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

@Service
public class StudentManagementService {

    private static final Logger log = LoggerFactory.getLogger(StudentManagementService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String REG_NUM_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final StudentRepository studentRepository;
    private final SubjectRepository subjectRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redisTemplate;

    public StudentManagementService(
            StudentRepository studentRepository,
            SubjectRepository subjectRepository,
            ApplicationEventPublisher eventPublisher,
            PasswordEncoder passwordEncoder,
            StringRedisTemplate redisTemplate) {
        this.studentRepository = studentRepository;
        this.subjectRepository = subjectRepository;
        this.eventPublisher = eventPublisher;
        this.passwordEncoder = passwordEncoder;
        this.redisTemplate = redisTemplate;
    }

    public record ProvisionedStudent(Student student, String oneTimePin) {
    }

    @Transactional
    public ProvisionedStudent createStudent(CreateStudentRequest request) {
        Set<Subject> subjects = resolveSubjects(request.subjectIds());
        String cleanCenter = request.examCenter() != null && !request.examCenter().trim().isEmpty()
                ? request.examCenter().trim()
                : null;
        return createStudentWithSubjects(request.name().trim(), cleanCenter, subjects);
    }

    @Transactional
    public ProvisionedStudent createStudentWithSubjects(String name, String examCenter, Set<Subject> subjects) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Student name is required");
        }
        String rawPin = generatePin();
        String regNum = generateUniqueRegistrationNumber();

        Student student = new Student();
        student.setName(name.trim());
        student.setExamCenter(examCenter != null && !examCenter.trim().isEmpty() ? examCenter.trim() : null);
        student.setRegistrationNumber(regNum);
        student.setPinHash(passwordEncoder.encode(rawPin));
        student.setPinResetRequired(false);
        student.setSubjects(subjects != null ? new HashSet<>(subjects) : new HashSet<>());

        Student saved = studentRepository.save(student);
        eventPublisher.publishEvent(new StudentCreatedEvent(
                saved.getId(),
                saved.getRegistrationNumber(),
                saved.getPinHash(),
                saved.isPinResetRequired()));
        return new ProvisionedStudent(saved, rawPin);
    }

    @Transactional
    public Student updateStudent(UUID id, UpdateStudentRequest request) {
        Student existing = studentRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Student not found"));

        existing.setName(request.name().trim());
        existing.setExamCenter(request.examCenter() != null && !request.examCenter().trim().isEmpty()
                ? request.examCenter().trim()
                : null);
        if (request.subjectIds() != null) {
            existing.setSubjects(resolveSubjects(request.subjectIds()));
        }

        Student saved = studentRepository.save(existing);
        eventPublisher.publishEvent(new StudentUpdatedEvent(saved.getId(), saved.getRegistrationNumber()));
        return saved;
    }

    @Transactional
    public void deleteStudent(UUID id) {
        studentRepository.findById(id).ifPresent(student -> {
            String registrationNumber = student.getRegistrationNumber();
            studentRepository.delete(student);
            evictStudentRedisKeys(registrationNumber);
            eventPublisher.publishEvent(new StudentDeletedEvent(student.getId(), registrationNumber));
        });
    }

    @Transactional
    public ResetPinResponse resetPin(UUID id) {
        Student student = studentRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Student not found"));

        String newPin = generatePin();
        student.setPinHash(passwordEncoder.encode(newPin));
        student.setPinResetRequired(false);
        Student saved = studentRepository.save(student);

        evictStudentRedisKeys(saved.getRegistrationNumber());
        eventPublisher.publishEvent(new StudentPinResetEvent(
                saved.getId(),
                saved.getRegistrationNumber(),
                saved.getPinHash(),
                false));
        return new ResetPinResponse(newPin, saved.getRegistrationNumber());
    }

    private Set<Subject> resolveSubjects(Set<UUID> subjectIds) {
        if (subjectIds == null || subjectIds.isEmpty()) {
            return new HashSet<>();
        }
        List<Subject> found = subjectRepository.findAllById(subjectIds);
        if (found.size() != subjectIds.size()) {
            throw new IllegalArgumentException("One or more requested subjects do not exist");
        }
        return new HashSet<>(found);
    }

    private void evictStudentRedisKeys(String registrationNumber) {
        if (redisTemplate == null || registrationNumber == null || registrationNumber.isBlank()) {
            return;
        }
        try {
            redisTemplate.delete(List.of(
                    SessionRedisKeys.sessionKey(registrationNumber),
                    SessionRedisKeys.examUnlockKey(registrationNumber),
                    SessionRedisKeys.loginFailIdKey(registrationNumber)));
        } catch (DataAccessException ex) {
            log.warn("Failed to evict Redis session keys for student credential update", ex);
        }
    }

    private String generatePin() {
        return String.valueOf(10000 + SECURE_RANDOM.nextInt(90000));
    }

    private String generateUniqueRegistrationNumber() {
        String regNum;
        do {
            regNum = generateRegistrationNumber();
        } while (studentRepository.existsByRegistrationNumberIgnoreCase(regNum));
        return regNum;
    }

    private String generateRegistrationNumber() {
        StringBuilder sb = new StringBuilder("JAMB-2026-");
        for (int i = 0; i < 6; i++) {
            sb.append(REG_NUM_CHARS.charAt(SECURE_RANDOM.nextInt(REG_NUM_CHARS.length())));
        }
        return sb.toString();
    }
}
