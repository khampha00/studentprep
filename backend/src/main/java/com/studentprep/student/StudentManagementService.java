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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

@Service
public class StudentManagementService {

    private static final Logger log = LoggerFactory.getLogger(StudentManagementService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String REG_NUM_LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private final StudentRepository studentRepository;
    private final SubjectRepository subjectRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redisTemplate;
    private final StudentPinCryptoService pinCryptoService;

    public StudentManagementService(
            StudentRepository studentRepository,
            SubjectRepository subjectRepository,
            ApplicationEventPublisher eventPublisher,
            PasswordEncoder passwordEncoder,
            StringRedisTemplate redisTemplate,
            StudentPinCryptoService pinCryptoService) {
        this.studentRepository = studentRepository;
        this.subjectRepository = subjectRepository;
        this.eventPublisher = eventPublisher;
        this.passwordEncoder = passwordEncoder;
        this.redisTemplate = redisTemplate;
        this.pinCryptoService = pinCryptoService;
    }

    public record ProvisionedStudent(Student student, String oneTimePin) {
    }

    private record IssuedPin(Student student, String rawPin) {
    }

    @Transactional
    public ProvisionedStudent createStudent(CreateStudentRequest request) {
        Set<Subject> subjects = resolveSubjects(request.subjectIds());
        return createStudentWithSubjects(request.name().trim(), subjects);
    }

    @Transactional
    public ProvisionedStudent createStudentWithSubjects(String name, Set<Subject> subjects) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Student name is required");
        }
        String rawPin = generatePin();
        String regNum = generateUniqueRegistrationNumber();

        Student student = new Student();
        student.setName(name.trim());
        student.setRegistrationNumber(regNum);
        student.setPinHash(passwordEncoder.encode(rawPin));
        student.setPinCiphertext(pinCryptoService.encrypt(rawPin));
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
        IssuedPin issued = issueFreshPin(student);
        return new ResetPinResponse(issued.rawPin(), issued.student().getRegistrationNumber());
    }

    /**
     * Returns the student's active decrypted PIN without mutation when recoverable ciphertext is
     * present; otherwise provisions and activates a fresh 5-digit PIN once for legacy/unissued
     * accounts. Fails fast if stored ciphertext fails authenticated decryption.
     */
    @Transactional
    public String ensureRecoverablePin(Student student) {
        if (student == null) {
            throw new IllegalArgumentException("Student must not be null");
        }
        return resolveOrProvisionPin(student);
    }

    /**
     * Batch variant of {@link #ensureRecoverablePin(Student)} preserving roster order and rolling
     * back atomically if any recoverable ciphertext fails decryption.
     */
    @Transactional
    public Map<UUID, String> ensureRecoverablePins(List<Student> students) {
        if (students == null || students.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> pinsByStudentId = new LinkedHashMap<>(students.size());
        for (Student student : students) {
            if (student == null) {
                continue;
            }
            pinsByStudentId.put(student.getId(), resolveOrProvisionPin(student));
        }
        return pinsByStudentId;
    }

    private String resolveOrProvisionPin(Student student) {
        if (!student.isPinResetRequired()
                && student.getPinCiphertext() != null
                && !student.getPinCiphertext().isBlank()) {
            return pinCryptoService.decrypt(student.getPinCiphertext());
        }
        return issueFreshPin(student).rawPin();
    }

    private IssuedPin issueFreshPin(Student student) {
        String newPin = generatePin();
        student.setPinHash(passwordEncoder.encode(newPin));
        student.setPinCiphertext(pinCryptoService.encrypt(newPin));
        student.setPinResetRequired(false);
        Student persisted = studentRepository.save(student);
        Student saved = persisted != null ? persisted : student;

        evictStudentRedisKeys(saved.getRegistrationNumber());
        eventPublisher.publishEvent(new StudentPinResetEvent(
                saved.getId(),
                saved.getRegistrationNumber(),
                saved.getPinHash(),
                false));
        return new IssuedPin(saved, newPin);
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
        StringBuilder sb = new StringBuilder(8);
        sb.append(String.format("%06d", SECURE_RANDOM.nextInt(1_000_000)));
        for (int i = 0; i < 2; i++) {
            sb.append(REG_NUM_LETTERS.charAt(SECURE_RANDOM.nextInt(REG_NUM_LETTERS.length())));
        }
        return sb.toString();
    }
}
