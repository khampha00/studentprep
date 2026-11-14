package com.studentprep.student;

import com.studentprep.questionbank.Subject;
import com.studentprep.questionbank.SubjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Component
@Profile("!prod")
@ConditionalOnProperty(name = "security.bootstrap.seed-non-prod-fixtures", havingValue = "true")
@Order(20)
public class NonProdTestFixtureSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(NonProdTestFixtureSeeder.class);
    private static final String DEV_SUPERVISOR_ID = "SUPERVISOR-001";
    private static final String DEV_STUDENT_REG = "123456JF";

    private final UserRepository userRepository;
    private final StudentRepository studentRepository;
    private final SubjectRepository subjectRepository;
    private final PasswordEncoder passwordEncoder;
    private final StudentPinCryptoService pinCryptoService;
    private final String devSupervisorPin;
    private final String devStudentPin;

    public NonProdTestFixtureSeeder(
            UserRepository userRepository,
            StudentRepository studentRepository,
            SubjectRepository subjectRepository,
            PasswordEncoder passwordEncoder,
            StudentPinCryptoService pinCryptoService,
            @Value("${security.bootstrap.dev-supervisor-pin:sup123456}") String devSupervisorPin,
            @Value("${security.bootstrap.dev-student-pin:73914}") String devStudentPin) {
        this.userRepository = userRepository;
        this.studentRepository = studentRepository;
        this.subjectRepository = subjectRepository;
        this.passwordEncoder = passwordEncoder;
        this.pinCryptoService = pinCryptoService;
        this.devSupervisorPin = devSupervisorPin;
        this.devStudentPin = devStudentPin;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedSupervisorIfAbsent();
        seedDemoStudentIfAbsent();
    }

    private void seedSupervisorIfAbsent() {
        if (userRepository.findByIdentifierIgnoreCase(DEV_SUPERVISOR_ID).isPresent()) {
            return;
        }
        User supervisor = new User();
        supervisor.setId(UUID.randomUUID());
        supervisor.setIdentifier(DEV_SUPERVISOR_ID);
        supervisor.setPinHash(passwordEncoder.encode(devSupervisorPin));
        supervisor.setRole("ROLE_SUPERVISOR");
        supervisor.setPinResetRequired(false);
        userRepository.save(supervisor);
        log.info("Seeded non-production supervisor fixture account {}", DEV_SUPERVISOR_ID);
    }

    private void seedDemoStudentIfAbsent() {
        if (studentRepository.findByRegistrationNumberIgnoreCase(DEV_STUDENT_REG).isPresent()) {
            return;
        }
        Set<Subject> subjects = new HashSet<>(subjectRepository.findAll());

        Student student = new Student();
        student.setName("Demo Candidate");
        student.setRegistrationNumber(DEV_STUDENT_REG);
        student.setPinHash(passwordEncoder.encode(devStudentPin));
        student.setPinCiphertext(pinCryptoService.encrypt(devStudentPin));
        student.setPinResetRequired(false);
        student.setSubjects(subjects);

        Student savedStudent = studentRepository.save(student);

        if (userRepository.findByIdentifierIgnoreCase(DEV_STUDENT_REG).isEmpty()
                && userRepository.findById(savedStudent.getId()).isEmpty()) {
            User user = new User();
            user.setId(savedStudent.getId());
            user.setIdentifier(savedStudent.getRegistrationNumber());
            user.setPinHash(savedStudent.getPinHash());
            user.setRole("ROLE_STUDENT");
            user.setPinResetRequired(false);
            userRepository.save(user);
        }
        log.info("Seeded non-production student fixture account {}", DEV_STUDENT_REG);
    }
}
