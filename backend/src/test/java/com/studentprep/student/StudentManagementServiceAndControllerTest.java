package com.studentprep.student;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.studentprep.common.GlobalExceptionHandler;
import com.studentprep.common.SessionRedisKeys;
import com.studentprep.questionbank.Subject;
import com.studentprep.questionbank.SubjectRepository;
import com.studentprep.security.PinHashingPasswordEncoder;
import com.studentprep.student.dto.ResetPinResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class StudentManagementServiceAndControllerTest {

    private static final String TEST_VAULT_KEY = "test_credential_vault_key_with_sufficient_entropy_01";

    @Mock
    private StudentRepository studentRepository;

    @Mock
    private SubjectRepository subjectRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private PasswordEncoder passwordEncoder;
    private StudentPinCryptoService pinCryptoService;
    private StudentManagementService studentManagementService;
    private ObjectMapper objectMapper;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        passwordEncoder = new PinHashingPasswordEncoder();
        pinCryptoService = new StudentPinCryptoService(TEST_VAULT_KEY);
        objectMapper = new ObjectMapper();
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        studentManagementService = new StudentManagementService(
                studentRepository,
                subjectRepository,
                eventPublisher,
                passwordEncoder,
                redisTemplate,
                pinCryptoService);
        CsvStudentImporter csvStudentImporter = new CsvStudentImporter(
                studentRepository,
                subjectRepository,
                studentManagementService);
        RegistrationSlipGenerator slipGenerator = new RegistrationSlipGenerator(
                studentRepository,
                studentManagementService);
        StudentAdminController controller = new StudentAdminController(
                csvStudentImporter,
                slipGenerator,
                studentRepository,
                studentManagementService,
                redisTemplate);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void createUpdateResetAndDeleteStudent_FollowsHashedAndEncryptedCredentialsEventsAndValidation() throws Exception {
        UUID subjectId = UUID.randomUUID();
        Subject biology = new Subject("BIOLOGY");
        biology.setId(subjectId);
        UUID assignedId = UUID.randomUUID();

        when(subjectRepository.findAllById(Set.of(subjectId))).thenReturn(List.of(biology));
        when(studentRepository.existsByRegistrationNumberIgnoreCase(anyString())).thenReturn(false);
        when(studentRepository.save(any(Student.class))).thenAnswer(invocation -> {
            Student entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(assignedId);
            }
            return entity;
        });

        mockMvc.perform(post("/api/v1/admin/students")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Validation failed"));

        String createResponse = mockMvc.perform(post("/api/v1/admin/students")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ada Lovelace\",\"subjectIds\":[\""
                                + subjectId + "\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(assignedId.toString()))
                .andExpect(jsonPath("$.data.name").value("Ada Lovelace"))
                .andExpect(jsonPath("$.data.oneTimePin").isNotEmpty())
                .andExpect(jsonPath("$.data.subjects[0].name").value("BIOLOGY"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(createResponse).doesNotContain("examCenter");
        assertThat(createResponse).doesNotContain("pinHash");
        assertThat(createResponse).doesNotContain("pinCiphertext");

        JsonNode createdData = objectMapper.readTree(createResponse).path("data");
        String createdRegNumber = createdData.path("registrationNumber").asText();
        String createdPin = createdData.path("oneTimePin").asText();
        assertThat(createdRegNumber).matches("^\\d{6}[A-Z]{2}$");

        ArgumentCaptor<Student> savedStudentCaptor = ArgumentCaptor.forClass(Student.class);
        verify(studentRepository).save(savedStudentCaptor.capture());
        Student createdEntity = savedStudentCaptor.getValue();
        assertThat(createdEntity.getRegistrationNumber()).isEqualTo(createdRegNumber);
        assertThat(createdEntity.getPinHash()).startsWith("$argon2id$");
        assertThat(createdEntity.getPinCiphertext()).startsWith("v1:");
        assertThat(pinCryptoService.decrypt(createdEntity.getPinCiphertext())).isEqualTo(createdPin);

        ArgumentCaptor<StudentCreatedEvent> createdEventCaptor = ArgumentCaptor.forClass(StudentCreatedEvent.class);
        verify(eventPublisher).publishEvent(createdEventCaptor.capture());
        StudentCreatedEvent createdEvent = createdEventCaptor.getValue();
        assertThat(createdEvent.studentId()).isEqualTo(assignedId);
        assertThat(createdEvent.pinHash()).startsWith("$argon2id$");
        assertThat(createdEvent.pinResetRequired()).isFalse();

        Student existing = new Student();
        existing.setId(assignedId);
        existing.setName("Ada Lovelace");
        existing.setRegistrationNumber(createdEvent.registrationNumber());
        existing.setPinHash(createdEvent.pinHash());
        existing.setPinCiphertext(createdEntity.getPinCiphertext());
        existing.setPinResetRequired(true);
        existing.setSubjects(Set.of(biology));

        when(studentRepository.findById(assignedId)).thenReturn(Optional.of(existing));
        when(studentRepository.findAll(PageRequest.of(0, 20))).thenReturn(new PageImpl<>(List.of(existing)));
        when(valueOperations.get(SessionRedisKeys.sessionKey(existing.getRegistrationNumber())))
                .thenReturn("active-jti");
        when(valueOperations.multiGet(List.of(SessionRedisKeys.sessionKey(existing.getRegistrationNumber()))))
                .thenReturn(List.of("active-jti"));

        String listJson = mockMvc.perform(get("/api/v1/admin/students"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].pinResetRequired").value(true))
                .andExpect(jsonPath("$.data[0].hasActiveSession").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(listJson).doesNotContain("examCenter");
        assertThat(listJson).doesNotContain("\"pin\"");
        assertThat(listJson).doesNotContain("pinHash");
        assertThat(listJson).doesNotContain("pinCiphertext");
        assertThat(listJson).doesNotContain(createdPin);

        String updateJson = mockMvc.perform(put("/api/v1/admin/students/" + assignedId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ada Byron\",\"subjectIds\":[\""
                                + subjectId + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Ada Byron"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(updateJson).doesNotContain("examCenter");

        ArgumentCaptor<StudentUpdatedEvent> updatedEventCaptor = ArgumentCaptor.forClass(StudentUpdatedEvent.class);
        verify(eventPublisher).publishEvent(updatedEventCaptor.capture());
        assertThat(updatedEventCaptor.getValue().registrationNumber()).isEqualTo(existing.getRegistrationNumber());

        ResetPinResponse reset = studentManagementService.resetPin(assignedId);
        assertThat(reset.newPin()).matches("^\\d{5}$");
        assertThat(existing.isPinResetRequired()).isFalse();
        assertThat(passwordEncoder.matches(reset.newPin(), existing.getPinHash())).isTrue();
        assertThat(existing.getPinCiphertext()).startsWith("v1:");
        assertThat(pinCryptoService.decrypt(existing.getPinCiphertext())).isEqualTo(reset.newPin());

        ArgumentCaptor<StudentPinResetEvent> resetEventCaptor = ArgumentCaptor.forClass(StudentPinResetEvent.class);
        verify(eventPublisher).publishEvent(resetEventCaptor.capture());
        assertThat(resetEventCaptor.getValue().newPinHash()).isEqualTo(existing.getPinHash());
        assertThat(resetEventCaptor.getValue().newPinHash()).doesNotContain(reset.newPin());

        mockMvc.perform(delete("/api/v1/admin/students/" + assignedId))
                .andExpect(status().isOk());

        verify(redisTemplate, times(2)).delete(List.of(
                SessionRedisKeys.sessionKey(existing.getRegistrationNumber()),
                SessionRedisKeys.examUnlockKey(existing.getRegistrationNumber()),
                SessionRedisKeys.loginFailIdKey(existing.getRegistrationNumber())));
        verify(eventPublisher).publishEvent(new StudentDeletedEvent(assignedId, existing.getRegistrationNumber()));
    }

    @Test
    void ensureRecoverablePin_DecryptsActiveWithoutRotationProvisionsLegacyAndFailsFastOnCorruptCiphertext() {
        String initialCiphertext = pinCryptoService.encrypt("03819");
        Student activeStudent = new Student();
        activeStudent.setId(UUID.randomUUID());
        activeStudent.setName("Active Candidate");
        activeStudent.setRegistrationNumber("JAMB-2026-ACT01");
        activeStudent.setPinHash("$argon2id$existinghash");
        activeStudent.setPinCiphertext(initialCiphertext);
        activeStudent.setPinResetRequired(false);

        String resolvedActive = studentManagementService.ensureRecoverablePin(activeStudent);
        assertThat(resolvedActive).isEqualTo("03819");
        assertThat(activeStudent.getPinCiphertext()).isEqualTo(initialCiphertext);
        assertThat(activeStudent.getPinHash()).isEqualTo("$argon2id$existinghash");
        verify(studentRepository, never()).save(any(Student.class));
        verifyNoInteractions(eventPublisher);

        Student corruptStudent = new Student();
        corruptStudent.setId(UUID.randomUUID());
        corruptStudent.setRegistrationNumber("JAMB-2026-BAD01");
        corruptStudent.setPinHash("$argon2id$hash");
        corruptStudent.setPinCiphertext("v1:invalid:payload");
        corruptStudent.setPinResetRequired(false);

        assertThatThrownBy(() -> studentManagementService.ensureRecoverablePin(corruptStudent))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unable to decrypt stored student PIN");
        verify(studentRepository, never()).save(any(Student.class));

        Student legacyStudent = new Student();
        legacyStudent.setId(UUID.randomUUID());
        legacyStudent.setName("Legacy Candidate");
        legacyStudent.setRegistrationNumber("JAMB-2026-LEG02");
        legacyStudent.setPinHash("!LEGACY_INVALIDATED!");
        legacyStudent.setPinCiphertext(null);
        legacyStudent.setPinResetRequired(true);
        when(studentRepository.save(legacyStudent)).thenReturn(legacyStudent);

        String provisionedPin = studentManagementService.ensureRecoverablePin(legacyStudent);
        assertThat(provisionedPin).matches("^\\d{5}$");
        assertThat(legacyStudent.isPinResetRequired()).isFalse();
        assertThat(passwordEncoder.matches(provisionedPin, legacyStudent.getPinHash())).isTrue();
        assertThat(pinCryptoService.decrypt(legacyStudent.getPinCiphertext())).isEqualTo(provisionedPin);

        verify(studentRepository, times(1)).save(legacyStudent);
        verify(redisTemplate, times(1)).delete(List.of(
                SessionRedisKeys.sessionKey("JAMB-2026-LEG02"),
                SessionRedisKeys.examUnlockKey("JAMB-2026-LEG02"),
                SessionRedisKeys.loginFailIdKey("JAMB-2026-LEG02")));
        ArgumentCaptor<StudentPinResetEvent> resetCaptor = ArgumentCaptor.forClass(StudentPinResetEvent.class);
        verify(eventPublisher, times(1)).publishEvent(resetCaptor.capture());
        assertThat(resetCaptor.getValue().pinResetRequired()).isFalse();
        assertThat(resetCaptor.getValue().newPinHash()).isEqualTo(legacyStudent.getPinHash());
    }

    @Test
    void controllerCredentialPdfEndpoints_ReturnNoStorePdfAndValidateBatchPayload() throws Exception {
        UUID studentId = UUID.randomUUID();
        Student candidate = new Student();
        candidate.setId(studentId);
        candidate.setName("Grace Hopper");
        candidate.setRegistrationNumber("JAMB-2026-PDF01");
        candidate.setPinHash("$argon2id$hash");
        candidate.setPinCiphertext(pinCryptoService.encrypt("58124"));
        candidate.setPinResetRequired(false);

        when(studentRepository.findAllByOrderByNameAscRegistrationNumberAsc()).thenReturn(List.of(candidate));
        when(studentRepository.findByRegistrationNumberInOrderByNameAscRegistrationNumberAsc(List.of("JAMB-2026-PDF01")))
                .thenReturn(List.of(candidate));
        when(studentRepository.findById(studentId)).thenReturn(Optional.of(candidate));

        MvcResult bulkResult = mockMvc.perform(get("/api/v1/admin/students/credentials/pdf"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(header().string(
                        HttpHeaders.CONTENT_DISPOSITION,
                        containsString("student_credentials_roster.pdf")))
                .andReturn();
        String bulkText = extractPdfText(bulkResult.getResponse().getContentAsByteArray());
        assertThat(bulkText).contains("Scope: All Registered Students", "Grace Hopper", "JAMB-2026-PDF01", "58124");
        assertThat(bulkText).doesNotContain("Exam Center");

        MvcResult batchResult = mockMvc.perform(post("/api/v1/admin/students/credentials/pdf")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"registrationNumbers\":[\"JAMB-2026-PDF01\"]}"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(header().string(
                        HttpHeaders.CONTENT_DISPOSITION,
                        containsString("batch_credentials_roster.pdf")))
                .andReturn();
        assertThat(extractPdfText(batchResult.getResponse().getContentAsByteArray()))
                .contains("Scope: Selected Batch Roster", "JAMB-2026-PDF01", "58124");

        MvcResult slipResult = mockMvc.perform(get("/api/v1/admin/students/" + studentId + "/slip"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("slip.pdf")))
                .andReturn();
        assertThat(extractPdfText(slipResult.getResponse().getContentAsByteArray()))
                .contains("Login PIN: 58124");

        mockMvc.perform(post("/api/v1/admin/students/credentials/pdf")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"registrationNumbers\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Validation failed"));

        mockMvc.perform(post("/api/v1/admin/students/credentials/pdf")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"registrationNumbers\":[\"   \"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Validation failed"));

        List<String> oversizedBatch = Collections.nCopies(5001, "JAMB-2026-0001");
        mockMvc.perform(post("/api/v1/admin/students/credentials/pdf")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("registrationNumbers", oversizedBatch))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Validation failed"));
    }

    @Test
    void nonProdTestFixtureSeeder_OnlyActiveOutsideProdAndSeedsHashedAndEncryptedFixturesIdempotently() {
        Profile profileAnnotation = NonProdTestFixtureSeeder.class.getAnnotation(Profile.class);
        assertThat(profileAnnotation).isNotNull();
        assertThat(profileAnnotation.value()).containsExactly("!prod");

        when(userRepository.findByIdentifierIgnoreCase("SUPERVISOR-001")).thenReturn(Optional.empty());
        when(studentRepository.findByRegistrationNumberIgnoreCase("123456JF")).thenReturn(Optional.empty());
        when(subjectRepository.findAll()).thenReturn(List.of());
        when(studentRepository.save(any(Student.class))).thenAnswer(invocation -> {
            Student candidate = invocation.getArgument(0);
            candidate.setId(UUID.randomUUID());
            return candidate;
        });
        when(userRepository.findByIdentifierIgnoreCase("123456JF")).thenReturn(Optional.empty());

        NonProdTestFixtureSeeder seeder = new NonProdTestFixtureSeeder(
                userRepository,
                studentRepository,
                subjectRepository,
                passwordEncoder,
                pinCryptoService,
                "sup123456",
                "73914");

        seeder.run(null);

        ArgumentCaptor<Student> studentCaptor = ArgumentCaptor.forClass(Student.class);
        verify(studentRepository, times(1)).save(studentCaptor.capture());
        Student seededStudent = studentCaptor.getValue();
        assertThat(seededStudent.getRegistrationNumber()).isEqualTo("123456JF");
        assertThat(seededStudent.getPinCiphertext()).startsWith("v1:");
        assertThat(pinCryptoService.decrypt(seededStudent.getPinCiphertext())).isEqualTo("73914");

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, times(2)).save(userCaptor.capture());
        List<User> savedUsers = userCaptor.getAllValues();
        assertThat(savedUsers.get(0).getIdentifier()).isEqualTo("SUPERVISOR-001");
        assertThat(savedUsers.get(0).getPinHash()).startsWith("$argon2id$");
        assertThat(savedUsers.get(1).getIdentifier()).isEqualTo("123456JF");
        assertThat(savedUsers.get(1).getPinHash()).startsWith("$argon2id$");
    }

    private static String extractPdfText(byte[] pdfBytes) throws Exception {
        assertThat(pdfBytes).isNotEmpty();
        assertThat(new String(pdfBytes, 0, Math.min(5, pdfBytes.length), StandardCharsets.US_ASCII))
                .isEqualTo("%PDF-");
        try (PdfReader reader = new PdfReader(pdfBytes)) {
            return new PdfTextExtractor(reader).getTextFromPage(1);
        }
    }
}
