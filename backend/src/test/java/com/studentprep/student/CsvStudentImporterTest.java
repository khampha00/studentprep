package com.studentprep.student;

import com.studentprep.questionbank.Subject;
import com.studentprep.questionbank.SubjectRepository;
import com.studentprep.security.PinHashingPasswordEncoder;
import com.studentprep.student.dto.BulkImportResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CsvStudentImporterTest {

    @Mock
    private StudentRepository studentRepository;

    @Mock
    private SubjectRepository subjectRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private StringRedisTemplate redisTemplate;

    private PasswordEncoder passwordEncoder;
    private StudentPinCryptoService pinCryptoService;
    private CsvStudentImporter csvStudentImporter;

    @BeforeEach
    void setUp() {
        passwordEncoder = new PinHashingPasswordEncoder();
        pinCryptoService = new StudentPinCryptoService("test_credential_vault_key_with_sufficient_entropy_01");
        StudentManagementService studentManagementService = new StudentManagementService(
                studentRepository,
                subjectRepository,
                eventPublisher,
                passwordEncoder,
                redisTemplate,
                pinCryptoService);
        csvStudentImporter = new CsvStudentImporter(
                studentRepository,
                subjectRepository,
                studentManagementService);
    }

    @Test
    void processBulkCsv_GeneratesOneTimePinsHashesCredentialsHandlesTrailingEmptyCellsAndRecordsErrors() {
        Subject biology = new Subject("BIOLOGY");
        biology.setId(UUID.randomUUID());
        Subject mathematics = new Subject("MATHEMATICS");
        mathematics.setId(UUID.randomUUID());

        when(subjectRepository.findByName("Biology")).thenReturn(Optional.of(biology));
        when(subjectRepository.findByName("Mathematics")).thenReturn(Optional.of(mathematics));
        when(studentRepository.existsByRegistrationNumberIgnoreCase(anyString())).thenReturn(false);
        when(studentRepository.save(any(Student.class))).thenAnswer(invocation -> {
            Student candidate = invocation.getArgument(0);
            candidate.setId(UUID.randomUUID());
            assertThat(candidate.getPinHash()).startsWith("$argon2id$");
            assertThat(candidate.getPinCiphertext()).startsWith("v1:");
            assertThat(candidate.isPinResetRequired()).isFalse();
            return candidate;
        });

        String csvContent = String.join("\n",
                "Name,Subject 1,Subject 2,Subject 3,Subject 4",
                "Ada Lovelace,Biology,,,",
                ",Biology,Mathematics,,",
                "BrokenRowOnlyOneColumn",
                "Grace Hopper,Mathematics,Biology,,");
        MockMultipartFile file = new MockMultipartFile(
                "csvFile",
                "students.csv",
                "text/csv",
                csvContent.getBytes(StandardCharsets.UTF_8));

        BulkImportResponse response = csvStudentImporter.processBulkCsv(file);

        assertThat(response.created()).isEqualTo(2);
        assertThat(response.errors()).hasSize(2);
        assertThat(response.errors().get(0)).contains("Row 2");
        assertThat(response.errors().get(1)).contains("Row 3", "expected 5 columns");
        assertThat(response.credentials()).hasSize(2);
        assertThat(response.credentials().get(0).name()).isEqualTo("Ada Lovelace");
        assertThat(response.credentials().get(0).registrationNumber()).matches("^\\d{6}[A-Z]{2}$");
        assertThat(response.credentials().get(0).oneTimePin()).matches("^\\d{5}$");
        assertThat(response.credentials().get(1).name()).isEqualTo("Grace Hopper");
        assertThat(response.credentials().get(1).registrationNumber()).matches("^\\d{6}[A-Z]{2}$");
        assertThat(response.credentials().get(1).oneTimePin()).matches("^\\d{5}$");
    }

    @Test
    void exportStudentsCsv_OmitsLoginPinColumnAndNeutralizesSpreadsheetFormulaInjection() {
        Subject dangerousSubject = new Subject("+HYPERLINK(\"http://evil\",\"bad\")");
        dangerousSubject.setId(UUID.randomUUID());
        Subject tabSubject = new Subject("\tmalicious");
        tabSubject.setId(UUID.randomUUID());
        Subject atSubject = new Subject("@SUM(1+1)");
        atSubject.setId(UUID.randomUUID());

        Student formulaStudent = new Student();
        formulaStudent.setId(UUID.randomUUID());
        formulaStudent.setName("=CMD|'/C calc'!A0");
        formulaStudent.setRegistrationNumber("JAMB-2026-ABC123");
        formulaStudent.setPinHash("$argon2id$v=19$m=16384,t=2,p=1$salt$supersecrethash");
        formulaStudent.setSubjects(Set.of(dangerousSubject));

        Student tabStudent = new Student();
        tabStudent.setId(UUID.randomUUID());
        tabStudent.setName("-2+3");
        tabStudent.setRegistrationNumber("JAMB-2026-XYZ987");
        tabStudent.setPinHash("$argon2id$v=19$m=16384,t=2,p=1$salt$anotherhash");
        tabStudent.setSubjects(Set.of(tabSubject));

        Student crStudent = new Student();
        crStudent.setId(UUID.randomUUID());
        crStudent.setName("\rmalicious");
        crStudent.setRegistrationNumber("JAMB-2026-CR0001");
        crStudent.setPinHash("!LEGACY_INVALIDATED!");
        crStudent.setSubjects(Set.of(atSubject));

        when(studentRepository.findAll()).thenReturn(List.of(formulaStudent, tabStudent, crStudent));

        byte[] csvBytes = csvStudentImporter.exportStudentsCsv();
        String csv = new String(csvBytes, StandardCharsets.UTF_8);

        String[] lines = csv.split("\n");
        assertThat(lines[0]).isEqualTo("Name,Registration Number,Registered Subjects");
        assertThat(csv).doesNotContain("Exam Center");
        assertThat(csv).doesNotContain("Login PIN");
        assertThat(csv).doesNotContain("$argon2id$");
        assertThat(csv).doesNotContain("!LEGACY_INVALIDATED!");

        assertThat(lines[1]).contains("\"'=CMD|'/C calc'!A0\"");
        assertThat(lines[1]).contains("\"'+HYPERLINK(\"\"http://evil\"\",\"\"bad\"\")\"");
        assertThat(lines[2]).contains("\"'-2+3\"");
        assertThat(lines[2]).contains("\"'\tmalicious\"");
        assertThat(lines[3]).contains("\"'\rmalicious\"");
        assertThat(lines[3]).contains("\"'@SUM(1+1)\"");
    }
}
