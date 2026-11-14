package com.studentprep.student;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.studentprep.questionbank.Subject;
import com.studentprep.questionbank.SubjectRepository;
import com.studentprep.security.PinHashingPasswordEncoder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegistrationSlipGeneratorTest {

    private static final String TEST_VAULT_KEY = "test_credential_vault_key_with_sufficient_entropy_01";

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
    private StudentManagementService studentManagementService;
    private RegistrationSlipGenerator registrationSlipGenerator;

    @BeforeEach
    void setUp() {
        passwordEncoder = new PinHashingPasswordEncoder();
        pinCryptoService = new StudentPinCryptoService(TEST_VAULT_KEY);
        studentManagementService = new StudentManagementService(
                studentRepository,
                subjectRepository,
                eventPublisher,
                passwordEncoder,
                redisTemplate,
                pinCryptoService);
        registrationSlipGenerator = new RegistrationSlipGenerator(studentRepository, studentManagementService);
    }

    @Test
    void getRegistrationSlipPdf_PrintsActiveDecryptedPinWithoutRotatingExistingPinOrExposingSecrets() throws Exception {
        UUID studentId = UUID.randomUUID();
        Subject physics = new Subject("PHYSICS");
        physics.setId(UUID.randomUUID());

        String encryptedPin = pinCryptoService.encrypt("04912");
        Student student = buildStudent(
                studentId,
                "Chidi Okonkwo",
                "JAMB-2026-SLIP01",
                "$argon2id$v=19$m=16384,t=2,p=1$salt$sensitivehashvalue",
                encryptedPin,
                false,
                Set.of(physics));

        when(studentRepository.findById(studentId)).thenReturn(Optional.of(student));

        byte[] firstPdfBytes = registrationSlipGenerator.getRegistrationSlipPdf(studentId);
        byte[] secondPdfBytes = registrationSlipGenerator.getRegistrationSlipPdf(studentId);

        assertPdfHeader(firstPdfBytes);
        assertPdfHeader(secondPdfBytes);
        verify(studentRepository, never()).save(any(Student.class));
        verifyNoInteractions(eventPublisher);

        String firstText = extractAllText(firstPdfBytes);
        String secondText = extractAllText(secondPdfBytes);

        assertThat(firstText).contains("Chidi Okonkwo");
        assertThat(firstText).contains("JAMB-2026-SLIP01");
        assertThat(firstText).contains("PHYSICS");
        assertThat(firstText).contains("Login PIN: 04912");
        assertThat(firstText).doesNotContain("Exam Center");
        assertThat(firstText).doesNotContain("$argon2id$");
        assertThat(firstText).doesNotContain("sensitivehashvalue");
        assertThat(firstText).doesNotContain(encryptedPin);
        assertThat(firstText).doesNotContain("Issued separately");
        assertThat(secondText).contains("Login PIN: 04912");
    }

    @Test
    void getRegistrationSlipPdf_LazilyBackfillsLegacyStudentOnceAndPreservesProvisionedPin() throws Exception {
        UUID studentId = UUID.randomUUID();
        Student legacyStudent = buildStudent(
                studentId,
                "Ngozi Eze",
                "JAMB-2026-LEG01",
                "!LEGACY_INVALIDATED!",
                null,
                true,
                Set.of());

        when(studentRepository.findById(studentId)).thenReturn(Optional.of(legacyStudent));
        when(studentRepository.save(any(Student.class))).thenAnswer(invocation -> invocation.getArgument(0));

        byte[] firstPdfBytes = registrationSlipGenerator.getRegistrationSlipPdf(studentId);
        byte[] secondPdfBytes = registrationSlipGenerator.getRegistrationSlipPdf(studentId);

        verify(studentRepository, times(1)).save(legacyStudent);
        ArgumentCaptor<StudentPinResetEvent> eventCaptor = ArgumentCaptor.forClass(StudentPinResetEvent.class);
        verify(eventPublisher, times(1)).publishEvent(eventCaptor.capture());

        assertThat(legacyStudent.isPinResetRequired()).isFalse();
        assertThat(legacyStudent.getPinHash()).startsWith("$argon2id$");
        assertThat(legacyStudent.getPinCiphertext()).startsWith("v1:");

        String provisionedPin = pinCryptoService.decrypt(legacyStudent.getPinCiphertext());
        assertThat(provisionedPin).matches("^\\d{5}$");
        assertThat(passwordEncoder.matches(provisionedPin, legacyStudent.getPinHash())).isTrue();
        assertThat(eventCaptor.getValue().studentId()).isEqualTo(studentId);
        assertThat(eventCaptor.getValue().pinResetRequired()).isFalse();

        String firstText = extractAllText(firstPdfBytes);
        String secondText = extractAllText(secondPdfBytes);
        assertThat(firstText).contains("Login PIN: " + provisionedPin);
        assertThat(secondText).contains("Login PIN: " + provisionedPin);
        assertThat(firstText).doesNotContain("!LEGACY_INVALIDATED!");
        assertThat(firstText).doesNotContain(legacyStudent.getPinCiphertext());
    }

    @Test
    void getRegistrationSlip_MetadataOmitsPinPinHashAndPinCiphertext() {
        UUID studentId = UUID.randomUUID();
        String encryptedPin = pinCryptoService.encrypt("73914");
        Student student = buildStudent(
                studentId,
                "Ngozi Eze",
                "JAMB-2026-SLIP02",
                "$argon2id$v=19$m=16384,t=2,p=1$salt$hash",
                encryptedPin,
                false,
                Set.of());

        when(studentRepository.findById(studentId)).thenReturn(Optional.of(student));

        Map<String, Object> slip = registrationSlipGenerator.getRegistrationSlip(studentId);

        assertThat(slip).containsEntry("name", "Ngozi Eze");
        assertThat(slip).containsEntry("registrationNumber", "JAMB-2026-SLIP02");
        assertThat(slip).containsEntry("pinResetRequired", false);
        assertThat(slip).doesNotContainKeys("pin", "pinHash", "pinCiphertext", "examCenter");
        assertThat(String.valueOf(slip.get("credentialStatus"))).contains("Issued separately (hashed)");
        assertThat(slip.toString()).doesNotContain("73914");
        assertThat(slip.toString()).doesNotContain(encryptedPin);
    }

    @Test
    void getBulkCredentialsPdf_RendersMultiPagePaginatedRosterWithRepeatingHeadersAndSortedPins() throws Exception {
        List<Student> students = new ArrayList<>();
        List<String> expectedPins = new ArrayList<>();

        for (int i = 0; i < 65; i++) {
            String pin = i == 0 ? "00419" : String.format("%05d", 10000 + i);
            String name = (i < 2) ? "Ada Alpha" : String.format("Candidate %03d", i);
            String regNum = String.format("JAMB-2026-B%04d", i);
            students.add(buildStudent(
                    UUID.randomUUID(),
                    name,
                    regNum,
                    "$argon2id$v=19$m=16384,t=2,p=1$salt$hash" + i,
                    pinCryptoService.encrypt(pin),
                    false,
                    Set.of()));
            expectedPins.add(pin);
        }

        when(studentRepository.findAllByOrderByNameAscRegistrationNumberAsc()).thenReturn(students);

        byte[] pdfBytes = registrationSlipGenerator.getBulkCredentialsPdf();
        assertPdfHeader(pdfBytes);
        verify(studentRepository, never()).save(any(Student.class));

        List<String> pages = extractPagesText(pdfBytes);
        assertThat(pages.size()).isGreaterThan(1);

        String page1 = pages.get(0);
        assertThat(page1).contains("StudentPrep Examination Credential Roster");
        assertThat(page1).contains("Scope: All Registered Students");
        assertThat(page1).contains("Confidential: For authorized examination administrator distribution only.");
        assertThat(page1).contains("Total Candidates: 65");
        assertThat(page1).contains("#");
        assertThat(page1).contains("Candidate Name");
        assertThat(page1).contains("Registration Number");
        assertThat(page1).contains("Login PIN");
        assertThat(page1).doesNotContain("Exam Center");

        String page2 = pages.get(1);
        assertThat(page2).contains("Candidate Name");
        assertThat(page2).contains("Registration Number");
        assertThat(page2).contains("Login PIN");
        assertThat(page2).doesNotContain("Exam Center");

        String fullText = String.join("\n", pages);
        assertThat(fullText).doesNotContain("$argon2id$");
        assertThat(fullText).doesNotContain("v1:");
        for (int i = 0; i < students.size(); i++) {
            Student s = students.get(i);
            assertThat(fullText).contains(s.getName());
            assertThat(fullText).contains(s.getRegistrationNumber());
            assertThat(fullText).contains(expectedPins.get(i));
        }
        assertThat(fullText.indexOf("JAMB-2026-B0000"))
                .isLessThan(fullText.indexOf("JAMB-2026-B0001"));
    }

    @Test
    void getBulkCredentialsPdf_RendersPopulatedAndEmptyRosterCleanly() throws Exception {
        Student abujaStudent = buildStudent(
                UUID.randomUUID(),
                "Bola Tinubu",
                "JAMB-2026-ABJ01",
                "$argon2id$hash",
                pinCryptoService.encrypt("55210"),
                false,
                Set.of());

        when(studentRepository.findAllByOrderByNameAscRegistrationNumberAsc())
                .thenReturn(List.of(abujaStudent))
                .thenReturn(List.of());

        byte[] populatedPdf = registrationSlipGenerator.getBulkCredentialsPdf();
        assertPdfHeader(populatedPdf);
        String populatedText = extractAllText(populatedPdf);
        assertThat(populatedText).contains("Scope: All Registered Students");
        assertThat(populatedText).contains("Confidential: For authorized examination administrator distribution only.");
        assertThat(populatedText).contains("Total Candidates: 1");
        assertThat(populatedText).contains("Bola Tinubu");
        assertThat(populatedText).contains("JAMB-2026-ABJ01");
        assertThat(populatedText).contains("55210");
        assertThat(populatedText).doesNotContain("Exam Center");

        byte[] emptyPdf = registrationSlipGenerator.getBulkCredentialsPdf();
        assertPdfHeader(emptyPdf);
        String emptyText = extractAllText(emptyPdf);
        assertThat(emptyText).contains("Total Candidates: 0");
        assertThat(emptyText).contains("No registered students found.");
    }

    @Test
    void getBatchCredentialsPdf_TrimsDeduplicatesPreservesOrderAndHandlesEmptyMatches() throws Exception {
        Student first = buildStudent(
                UUID.randomUUID(),
                "Alpha Candidate",
                "JAMB-2026-A",
                "$argon2id$hashA",
                pinCryptoService.encrypt("11111"),
                false,
                Set.of());
        Student second = buildStudent(
                UUID.randomUUID(),
                "Beta Candidate",
                "JAMB-2026-B",
                "$argon2id$hashB",
                pinCryptoService.encrypt("22222"),
                false,
                Set.of());

        when(studentRepository.findByRegistrationNumberInOrderByNameAscRegistrationNumberAsc(
                List.of("JAMB-2026-B", "JAMB-2026-A")))
                .thenReturn(List.of(first, second));

        byte[] batchPdf = registrationSlipGenerator.getBatchCredentialsPdf(
                List.of("  JAMB-2026-B  ", "JAMB-2026-A", "JAMB-2026-A", "   "));
        assertPdfHeader(batchPdf);

        String batchText = extractAllText(batchPdf);
        assertThat(batchText).contains("Scope: Selected Batch Roster");
        assertThat(batchText).contains("Total Candidates: 2");
        assertThat(batchText).contains("Alpha Candidate");
        assertThat(batchText).contains("11111");
        assertThat(batchText).contains("Beta Candidate");
        assertThat(batchText).contains("22222");
        assertThat(batchText).doesNotContain("Exam Center");
        assertThat(batchText.indexOf("Alpha Candidate")).isLessThan(batchText.indexOf("Beta Candidate"));

        byte[] emptyBatchPdf = registrationSlipGenerator.getBatchCredentialsPdf(List.of());
        assertPdfHeader(emptyBatchPdf);
        String emptyBatchText = extractAllText(emptyBatchPdf);
        assertThat(emptyBatchText).contains("Scope: Selected Batch Roster");
        assertThat(emptyBatchText).contains("Total Candidates: 0");
        assertThat(emptyBatchText).contains("No registered students found.");
    }

    private static Student buildStudent(
            UUID id,
            String name,
            String regNum,
            String pinHash,
            String pinCiphertext,
            boolean pinResetRequired,
            Set<Subject> subjects) {
        Student student = new Student();
        student.setId(id);
        student.setName(name);
        student.setRegistrationNumber(regNum);
        student.setPinHash(pinHash);
        student.setPinCiphertext(pinCiphertext);
        student.setPinResetRequired(pinResetRequired);
        student.setSubjects(subjects);
        return student;
    }

    private static void assertPdfHeader(byte[] pdfBytes) {
        assertThat(pdfBytes).isNotEmpty();
        assertThat(new String(pdfBytes, 0, Math.min(5, pdfBytes.length), StandardCharsets.US_ASCII))
                .isEqualTo("%PDF-");
    }

    private static String extractAllText(byte[] pdfBytes) throws Exception {
        return String.join("\n", extractPagesText(pdfBytes));
    }

    private static List<String> extractPagesText(byte[] pdfBytes) throws Exception {
        try (PdfReader reader = new PdfReader(pdfBytes)) {
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            List<String> pages = new ArrayList<>(reader.getNumberOfPages());
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                pages.add(extractor.getTextFromPage(page));
            }
            return pages;
        }
    }
}
