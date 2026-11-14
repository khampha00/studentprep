package com.studentprep.student;

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
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class StudentManagementServiceAndControllerTest {

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
    private StudentManagementService studentManagementService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        passwordEncoder = new PinHashingPasswordEncoder();
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        studentManagementService = new StudentManagementService(
                studentRepository,
                subjectRepository,
                eventPublisher,
                passwordEncoder,
                redisTemplate);
        CsvStudentImporter csvStudentImporter = new CsvStudentImporter(
                studentRepository,
                subjectRepository,
                studentManagementService);
        RegistrationSlipGenerator slipGenerator = new RegistrationSlipGenerator(studentRepository);
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
    void createUpdateResetAndDeleteStudent_FollowsHashedCredentialsEventsAndValidation() throws Exception {
        UUID subjectId = UUID.randomUUID();
        Subject biology = new Subject("BIOLOGY");
        biology.setId(subjectId);
        UUID assignedId = UUID.randomUUID();

        when(subjectRepository.findAllById(Set.of(subjectId))).thenReturn(List.of(biology));
        when(studentRepository.existsByRegistrationNumberIgnoreCase(anyString())).thenReturn(false);
        when(studentRepository.save(any(Student.class))).thenAnswer(invocation -> {
            Student entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                // Verify service did not pre-assign UUID manually before save
                entity.setId(assignedId);
            }
            return entity;
        });

        mockMvc.perform(post("/api/v1/admin/students")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \",\"examCenter\":\"Center 1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Validation failed"));

        String createResponse = mockMvc.perform(post("/api/v1/admin/students")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ada Lovelace\",\"examCenter\":\"Center 1\",\"subjectIds\":[\""
                                + subjectId + "\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(assignedId.toString()))
                .andExpect(jsonPath("$.data.name").value("Ada Lovelace"))
                .andExpect(jsonPath("$.data.oneTimePin").isNotEmpty())
                .andExpect(jsonPath("$.data.subjects[0].name").value("BIOLOGY"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(createResponse).doesNotContain("pinHash");

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
        existing.setExamCenter("Center 1");
        existing.setPinHash(createdEvent.pinHash());
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
        assertThat(listJson).doesNotContain("\"pin\"");
        assertThat(listJson).doesNotContain("pinHash");

        mockMvc.perform(put("/api/v1/admin/students/" + assignedId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ada Byron\",\"examCenter\":\"Center 2\",\"subjectIds\":[\""
                                + subjectId + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Ada Byron"))
                .andExpect(jsonPath("$.data.examCenter").value("Center 2"));

        ArgumentCaptor<StudentUpdatedEvent> updatedEventCaptor = ArgumentCaptor.forClass(StudentUpdatedEvent.class);
        verify(eventPublisher).publishEvent(updatedEventCaptor.capture());
        assertThat(updatedEventCaptor.getValue().registrationNumber()).isEqualTo(existing.getRegistrationNumber());

        ResetPinResponse reset = studentManagementService.resetPin(assignedId);
        assertThat(reset.newPin()).matches("^\\d{5}$");
        assertThat(existing.isPinResetRequired()).isFalse();
        assertThat(passwordEncoder.matches(reset.newPin(), existing.getPinHash())).isTrue();

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
    void nonProdTestFixtureSeeder_OnlyActiveOutsideProdAndSeedsHashedFixturesIdempotently() {
        Profile profileAnnotation = NonProdTestFixtureSeeder.class.getAnnotation(Profile.class);
        assertThat(profileAnnotation).isNotNull();
        assertThat(profileAnnotation.value()).containsExactly("!prod");

        when(userRepository.findByIdentifierIgnoreCase("SUPERVISOR-001")).thenReturn(Optional.empty());
        when(studentRepository.findByRegistrationNumberIgnoreCase("JAMB-2026-DEMO01")).thenReturn(Optional.empty());
        when(subjectRepository.findAll()).thenReturn(List.of());
        when(studentRepository.save(any(Student.class))).thenAnswer(invocation -> {
            Student candidate = invocation.getArgument(0);
            candidate.setId(UUID.randomUUID());
            return candidate;
        });
        when(userRepository.findByIdentifierIgnoreCase("JAMB-2026-DEMO01")).thenReturn(Optional.empty());

        NonProdTestFixtureSeeder seeder = new NonProdTestFixtureSeeder(
                userRepository,
                studentRepository,
                subjectRepository,
                passwordEncoder,
                "sup123456",
                "73914");

        seeder.run(null);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, times(2)).save(userCaptor.capture());
        List<User> savedUsers = userCaptor.getAllValues();
        assertThat(savedUsers.get(0).getIdentifier()).isEqualTo("SUPERVISOR-001");
        assertThat(savedUsers.get(0).getPinHash()).startsWith("$argon2id$");
        assertThat(savedUsers.get(1).getIdentifier()).isEqualTo("JAMB-2026-DEMO01");
        assertThat(savedUsers.get(1).getPinHash()).startsWith("$argon2id$");
    }
}
