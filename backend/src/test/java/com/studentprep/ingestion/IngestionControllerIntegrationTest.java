package com.studentprep.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studentprep.common.SessionRedisKeys;
import com.studentprep.ingestion.job.IngestionJobRepository;
import com.studentprep.questionbank.Question;
import com.studentprep.questionbank.QuestionContextRepository;
import com.studentprep.questionbank.QuestionRepository;
import com.studentprep.questionbank.QuestionStatus;
import com.studentprep.questionbank.Subject;
import com.studentprep.questionbank.SubjectRepository;
import com.studentprep.security.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.services.s3.S3Client;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "s3.endpoint=http://minio:9000",
        "s3.public-base-url=https://assets.studentprep.example/cdn/",
        "s3.bucket=studentprep-assets",
        "gemini.api-key=AIzaSyTestConfiguredGeminiKey9876543210",
        "gemini.model=gemini-3.5-flash-lite",
        "docling.api-key=docling-test-secret-xyz",
        "ingestion.retry-delay-ms=10"
})
@AutoConfigureMockMvc
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class IngestionControllerIntegrationTest {

    private static final String TEST_GEMINI_KEY = "AIzaSyTestConfiguredGeminiKey9876543210";
    private static final String TEST_DOCLING_KEY = "docling-test-secret-xyz";

    private final MockMvc mockMvc;
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final IngestionJobRepository ingestionJobRepository;
    private final SubjectRepository subjectRepository;
    private final QuestionRepository questionRepository;
    private final QuestionContextRepository questionContextRepository;

    @MockBean
    private S3Client s3Client;

    @MockBean
    private LlmStructuringService mockedLlmStructuringService;

    @MockBean
    private DoclingService mockedDoclingService;

    private final List<String> createdRedisKeys = new ArrayList<>();
    private final List<UUID> createdJobIds = new ArrayList<>();
    private final List<UUID> createdSubjectIds = new ArrayList<>();

    IngestionControllerIntegrationTest(
            MockMvc mockMvc,
            JwtUtil jwtUtil,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            IngestionJobRepository ingestionJobRepository,
            SubjectRepository subjectRepository,
            QuestionRepository questionRepository,
            QuestionContextRepository questionContextRepository) {
        this.mockMvc = mockMvc;
        this.jwtUtil = jwtUtil;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ingestionJobRepository = ingestionJobRepository;
        this.subjectRepository = subjectRepository;
        this.questionRepository = questionRepository;
        this.questionContextRepository = questionContextRepository;
    }

    @AfterEach
    void cleanup() {
        for (UUID jobId : createdJobIds) {
            List<Question> jobQuestions = questionRepository.findByIngestionJobIdOrderByCreatedAtAsc(jobId);
            if (!jobQuestions.isEmpty()) {
                questionRepository.deleteAll(jobQuestions);
            }
        }

        for (UUID subjectId : createdSubjectIds) {
            questionContextRepository.deleteBySubject_Id(subjectId);
        }

        for (UUID jobId : createdJobIds) {
            ingestionJobRepository.deleteById(jobId);
        }
        createdJobIds.clear();

        for (UUID subjectId : createdSubjectIds) {
            subjectRepository.deleteById(subjectId);
        }
        createdSubjectIds.clear();

        for (String redisKey : createdRedisKeys) {
            redisTemplate.delete(redisKey);
        }
        createdRedisKeys.clear();
    }

    @Test
    void ingestPdf_Returns202ImmediatelyWhileDoclingRunsAsyncAndPersistsJobScopedQuestions() throws Exception {
        String adminToken = issueAdminToken();
        Subject subject = createSubject();

        CountDownLatch doclingStarted = new CountDownLatch(1);
        CountDownLatch allowDoclingFinish = new CountDownLatch(1);

        when(mockedDoclingService.parsePdf(any())).thenAnswer(invocation -> {
            doclingStarted.countDown();
            allowDoclingFinish.await(5, TimeUnit.SECONDS);
            return "1. Passage Q1\n2. Follow-up Q2\n3. Standalone Q3";
        });

        JsonNode structuredQuestions = objectMapper.readTree("""
                [
                  {
                    "type": "MCQ",
                    "text": "Reading comprehension Q1",
                    "options": {"A": "1", "B": "2"},
                    "correctOption": "A",
                    "shared_context": "Long comprehension passage.",
                    "is_follow_up": false,
                    "questionNumber": "1"
                  },
                  {
                    "type": "MCQ",
                    "text": "Follow-up Q2",
                    "options": {"A": "3", "B": "4"},
                    "correctOption": "B",
                    "shared_context": "",
                    "is_follow_up": true,
                    "questionNumber": "2"
                  },
                  {
                    "type": "MCQ",
                    "text": "Standalone Q3",
                    "options": {"A": "5", "B": "6"},
                    "correctOption": "A",
                    "shared_context": "",
                    "is_follow_up": false,
                    "questionNumber": "3"
                  }
                ]
                """);
        when(mockedLlmStructuringService.structureChunk(anyString(), nullable(String.class)))
                .thenReturn(structuredQuestions);

        MockMultipartFile validPdf = new MockMultipartFile(
                "file",
                "exam.pdf",
                "application/pdf",
                "%PDF-1.4\n1 0 obj\n<<>>\nendobj\n%%EOF".getBytes(StandardCharsets.UTF_8)
        );

        UUID jobId = null;
        try {
            String acceptedJson = mockMvc.perform(multipart("/api/v1/admin/ingestion/pdf")
                            .file(validPdf)
                            .param("subjectId", subject.getId().toString())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.data.jobId").isNotEmpty())
                    .andExpect(jsonPath("$.data.status").value("PENDING"))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            jobId = UUID.fromString(objectMapper.readTree(acceptedJson).path("data").path("jobId").asText());
            createdJobIds.add(jobId);

            assertThat(doclingStarted.await(5, TimeUnit.SECONDS)).isTrue();

            String inFlightJson = mockMvc.perform(get("/api/v1/admin/ingestion/jobs/{jobId}", jobId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            String inFlightStatus = objectMapper.readTree(inFlightJson).path("data").path("status").asText();
            assertThat(inFlightStatus).isIn("PENDING", "PROCESSING");
        } finally {
            allowDoclingFinish.countDown();
        }

        JsonNode completedPayload = awaitTerminalJobViaApi(adminToken, jobId);
        assertThat(completedPayload.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(completedPayload.path("totalChunks").asInt()).isEqualTo(1);
        assertThat(completedPayload.path("processedChunks").asInt()).isEqualTo(1);

        UUID expectedJobId = jobId;
        List<Question> savedQuestions = questionRepository.findByIngestionJobIdOrderByCreatedAtAsc(expectedJobId);
        assertThat(savedQuestions).hasSize(3);
        assertThat(savedQuestions).allSatisfy(q -> {
            assertThat(q.getStatus()).isEqualTo(QuestionStatus.DRAFT);
            assertThat(q.getIngestionJobId()).isEqualTo(expectedJobId);
        });
        assertThat(savedQuestions.get(0).getContextId()).isNotNull();
        assertThat(savedQuestions.get(1).getContextId()).isEqualTo(savedQuestions.get(0).getContextId());
        assertThat(savedQuestions.get(2).getContextId()).isNull();
    }

    @Test
    void ingestPdf_DoclingFailureInBackground_TransitionsJobToFailedWithSanitizedError() throws Exception {
        String adminToken = issueAdminToken();
        Subject subject = createSubject();

        when(mockedDoclingService.parsePdf(any()))
                .thenThrow(new IllegalStateException(
                        "Docling sidecar crashed with " + TEST_DOCLING_KEY + " and " + TEST_GEMINI_KEY
                ));

        MockMultipartFile validPdf = new MockMultipartFile(
                "file",
                "broken.pdf",
                "application/pdf",
                "%PDF-1.4\n%%EOF".getBytes(StandardCharsets.UTF_8)
        );

        String acceptedJson = mockMvc.perform(multipart("/api/v1/admin/ingestion/pdf")
                        .file(validPdf)
                        .param("subjectId", subject.getId().toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.jobId").isNotEmpty())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        UUID jobId = UUID.fromString(objectMapper.readTree(acceptedJson).path("data").path("jobId").asText());
        createdJobIds.add(jobId);

        JsonNode failedPayload = awaitTerminalJobViaApi(adminToken, jobId);
        assertThat(failedPayload.path("status").asText()).isEqualTo("FAILED");
        String errorMessage = failedPayload.path("errorMessage").asText();
        assertThat(errorMessage)
                .isNotBlank()
                .contains("[REDACTED]")
                .doesNotContain(TEST_DOCLING_KEY)
                .doesNotContain(TEST_GEMINI_KEY);
    }

    @Test
    void ingestPdf_AndJobPolling_ReturnProblemDetailsForInvalidFileAndMissingResources() throws Exception {
        String adminToken = issueAdminToken();
        Subject subject = createSubject();

        MockMultipartFile nonPdfExtension = new MockMultipartFile(
                "file",
                "notes.txt",
                "application/pdf",
                "%PDF-1.4\n%%EOF".getBytes(StandardCharsets.UTF_8)
        );
        mockMvc.perform(multipart("/api/v1/admin/ingestion/pdf")
                        .file(nonPdfExtension)
                        .param("subjectId", subject.getId().toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").isNotEmpty());

        MockMultipartFile validPdf = new MockMultipartFile(
                "file",
                "exam.pdf",
                "application/pdf",
                "%PDF-1.4\n%%EOF".getBytes(StandardCharsets.UTF_8)
        );
        mockMvc.perform(multipart("/api/v1/admin/ingestion/pdf")
                        .file(validPdf)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        mockMvc.perform(multipart("/api/v1/admin/ingestion/pdf")
                        .file(validPdf)
                        .param("subjectId", UUID.randomUUID().toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        mockMvc.perform(get("/api/v1/admin/ingestion/jobs/{jobId}", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    private JsonNode awaitTerminalJobViaApi(String adminToken, UUID jobId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        JsonNode dataNode = null;
        while (System.nanoTime() < deadline) {
            String json = mockMvc.perform(get("/api/v1/admin/ingestion/jobs/{jobId}", jobId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            dataNode = objectMapper.readTree(json).path("data");
            String status = dataNode.path("status").asText();
            if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
                return dataNode;
            }
            Thread.sleep(25);
        }
        return dataNode;
    }

    private String issueAdminToken() {
        String identifier = "INGEST-CTRL-ADMIN-" + UUID.randomUUID();
        String jti = UUID.randomUUID().toString();
        String redisKey = SessionRedisKeys.sessionKey(identifier);
        redisTemplate.opsForValue().set(redisKey, jti, Duration.ofMinutes(5));
        createdRedisKeys.add(redisKey);
        return jwtUtil.generateToken(identifier, "ROLE_ADMIN", jti);
    }

    private Subject createSubject() {
        Subject subject = new Subject("INGESTION-INT-" + UUID.randomUUID());
        Subject saved = subjectRepository.save(subject);
        createdSubjectIds.add(saved.getId());
        return saved;
    }
}
