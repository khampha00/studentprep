package com.studentprep.ingestion;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studentprep.common.SessionRedisKeys;
import com.studentprep.ingestion.job.IngestionJob;
import com.studentprep.ingestion.job.IngestionJobRepository;
import com.studentprep.ingestion.job.IngestionJobStatus;
import com.studentprep.questionbank.Subject;
import com.studentprep.questionbank.SubjectRepository;
import com.studentprep.security.JwtUtil;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
class IngestionSecurityTest {

    private static final String TEST_GEMINI_KEY = "AIzaSyTestConfiguredGeminiKey9876543210";
    private static final String TEST_DOCLING_KEY = "docling-test-secret-xyz";

    private final MockMvc mockMvc;
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AsyncIngestionWorker asyncIngestionWorker;
    private final IngestionJobRepository ingestionJobRepository;
    private final SubjectRepository subjectRepository;

    @MockBean
    private S3Client s3Client;

    @MockBean
    private LlmStructuringService mockedLlmStructuringService;

    @MockBean
    private DoclingService mockedDoclingService;

    private final List<String> createdRedisKeys = new ArrayList<>();
    private final List<UUID> createdJobIds = new ArrayList<>();
    private final List<UUID> createdSubjectIds = new ArrayList<>();

    IngestionSecurityTest(
            MockMvc mockMvc,
            JwtUtil jwtUtil,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            AsyncIngestionWorker asyncIngestionWorker,
            IngestionJobRepository ingestionJobRepository,
            SubjectRepository subjectRepository) {
        this.mockMvc = mockMvc;
        this.jwtUtil = jwtUtil;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.asyncIngestionWorker = asyncIngestionWorker;
        this.ingestionJobRepository = ingestionJobRepository;
        this.subjectRepository = subjectRepository;
    }

    @AfterEach
    void cleanup() {
        for (String redisKey : createdRedisKeys) {
            redisTemplate.delete(redisKey);
        }
        createdRedisKeys.clear();

        for (UUID jobId : createdJobIds) {
            ingestionJobRepository.deleteById(jobId);
        }
        createdJobIds.clear();

        for (UUID subjectId : createdSubjectIds) {
            subjectRepository.deleteById(subjectId);
        }
        createdSubjectIds.clear();
    }

    @Test
    void uploadAsset_RequiresAdminRoleAndReturnsProblemDetailsForUnauthorizedOrSupervisor() throws Exception {
        MockMultipartFile validPng = new MockMultipartFile(
                "file", "diagram.png", "image/png", validPngBytes()
        );

        mockMvc.perform(multipart("/api/v1/admin/ingestion/assets").file(validPng))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        String supervisorToken = issueSessionToken("INGEST-SUP-" + UUID.randomUUID(), "ROLE_SUPERVISOR");
        mockMvc.perform(multipart("/api/v1/admin/ingestion/assets")
                        .file(validPng)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + supervisorToken))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoInteractions(s3Client);
    }

    @Test
    void uploadAsset_RejectsSvgHtmlSpoofedScriptsEmptyAndOversizedWithProblemDetailsAndZeroS3Writes() throws Exception {
        String adminToken = issueSessionToken("INGEST-ADMIN-" + UUID.randomUUID(), "ROLE_ADMIN");

        MockMultipartFile svgFile = new MockMultipartFile(
                "file",
                "xss.svg",
                "image/svg+xml",
                "<svg xmlns=\"http://www.w3.org/2000/svg\" onload=\"alert(1)\"></svg>".getBytes(StandardCharsets.UTF_8)
        );
        performAssetUpload(adminToken, svgFile)
                .andExpect(status().is(anyOf(is(400), is(415))))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").isNotEmpty());

        MockMultipartFile htmlFile = new MockMultipartFile(
                "file",
                "payload.html",
                "text/html",
                "<html><script>alert('xss')</script></html>".getBytes(StandardCharsets.UTF_8)
        );
        performAssetUpload(adminToken, htmlFile)
                .andExpect(status().is(anyOf(is(400), is(415))))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").isNotEmpty());

        MockMultipartFile spoofedPng = new MockMultipartFile(
                "file",
                "spoofed.png",
                "image/png",
                "<script>fetch('https://evil.test')</script>".getBytes(StandardCharsets.UTF_8)
        );
        performAssetUpload(adminToken, spoofedPng)
                .andExpect(status().is(anyOf(is(400), is(415))))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").isNotEmpty());

        MockMultipartFile mismatchedMime = new MockMultipartFile(
                "file",
                "photo.jpg",
                "image/jpeg",
                validPngBytes()
        );
        performAssetUpload(adminToken, mismatchedMime)
                .andExpect(status().is(anyOf(is(400), is(415))))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        MockMultipartFile fakeWebp = new MockMultipartFile(
                "file",
                "fake.webp",
                "image/webp",
                new byte[]{'R', 'I', 'F', 'F', 0x24, 0x00, 0x00, 0x00, 'A', 'V', 'I', ' '}
        );
        performAssetUpload(adminToken, fakeWebp)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        MockMultipartFile emptyFile = new MockMultipartFile(
                "file",
                "empty.png",
                "image/png",
                new byte[0]
        );
        performAssetUpload(adminToken, emptyFile)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        byte[] oversized = new byte[(5 * 1024 * 1024) + 1];
        System.arraycopy(validPngBytes(), 0, oversized, 0, validPngBytes().length);
        MockMultipartFile oversizedPng = new MockMultipartFile(
                "file",
                "huge.png",
                "image/png",
                oversized
        );
        performAssetUpload(adminToken, oversizedPng)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoInteractions(s3Client);
    }

    @Test
    void uploadAsset_AcceptsValidRasterFormatsWithUuidKeysAndConfiguredPublicBaseUrl() throws Exception {
        String adminToken = issueSessionToken("INGEST-ADMIN-" + UUID.randomUUID(), "ROLE_ADMIN");
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        assertValidAssetUpload(adminToken, "../../evil-name.svg", "image/png", validPngBytes(), ".png");
        assertValidAssetUpload(adminToken, "photo.jpeg", "image/jpeg", validJpegBytes(), ".jpg");
        assertValidAssetUpload(adminToken, "anim.gif", "image/gif", validGifBytes(), ".gif");
        assertValidAssetUpload(adminToken, "diagram.webp", "image/webp", validWebpBytes(), ".webp");
    }

    @Test
    void ingestPdf_ValidatesPdfMagicBytesContentTypeAndSubjectExistenceWithProblemDetails() throws Exception {
        String adminToken = issueSessionToken("INGEST-ADMIN-" + UUID.randomUUID(), "ROLE_ADMIN");
        Subject subject = createSubject();

        MockMultipartFile spoofedPdf = new MockMultipartFile(
                "file",
                "fake.pdf",
                "application/pdf",
                "<html>not a pdf</html>".getBytes(StandardCharsets.UTF_8)
        );
        mockMvc.perform(multipart("/api/v1/admin/ingestion/pdf")
                        .file(spoofedPdf)
                        .param("subjectId", subject.getId().toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").isNotEmpty());

        MockMultipartFile nonPdfMime = new MockMultipartFile(
                "file",
                "doc.html",
                "text/html",
                "%PDF-1.4\n%fake".getBytes(StandardCharsets.UTF_8)
        );
        mockMvc.perform(multipart("/api/v1/admin/ingestion/pdf")
                        .file(nonPdfMime)
                        .param("subjectId", subject.getId().toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().is(anyOf(is(400), is(415))))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        MockMultipartFile validPdf = new MockMultipartFile(
                "file",
                "exam.pdf",
                "application/pdf",
                "%PDF-1.4\n1 0 obj\n<<>>\nendobj\n%%EOF".getBytes(StandardCharsets.UTF_8)
        );
        mockMvc.perform(multipart("/api/v1/admin/ingestion/pdf")
                        .file(validPdf)
                        .param("subjectId", UUID.randomUUID().toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        byte[] oversizedPdfBytes = new byte[(10 * 1024 * 1024) + 1];
        byte[] pdfMagic = "%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(pdfMagic, 0, oversizedPdfBytes, 0, pdfMagic.length);
        MockMultipartFile oversizedPdf = new MockMultipartFile(
                "file",
                "huge.pdf",
                "application/pdf",
                oversizedPdfBytes
        );
        mockMvc.perform(multipart("/api/v1/admin/ingestion/pdf")
                        .file(oversizedPdf)
                        .param("subjectId", subject.getId().toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoInteractions(mockedDoclingService);

        when(mockedDoclingService.parsePdf(any())).thenReturn("1. Sample parsed question?");
        when(mockedLlmStructuringService.structureChunk(anyString(), nullable(String.class)))
                .thenReturn(objectMapper.readTree("[]"));
        String responseJson = mockMvc.perform(multipart("/api/v1/admin/ingestion/pdf")
                        .file(validPdf)
                        .param("subjectId", subject.getId().toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.jobId").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();

        UUID acceptedJobId = UUID.fromString(objectMapper.readTree(responseJson).path("data").path("jobId").asText());
        createdJobIds.add(acceptedJobId);
        IngestionJob completedJob = awaitTerminalJob(acceptedJobId);
        assertThat(completedJob.getStatus()).isEqualTo(IngestionJobStatus.COMPLETED);
    }

    @Test
    void corsPreflight_RejectsUnconfiguredOriginsAndAllowsConfiguredOrigins() throws Exception {
        mockMvc.perform(options("/api/v1/admin/ingestion/assets")
                        .header(HttpHeaders.ORIGIN, "https://evil.attacker.test")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));

        mockMvc.perform(options("/api/v1/admin/ingestion/assets")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @Test
    void llmStructuringService_SendsApiKeyInHeaderWithoutQueryParamAndSanitizesUpstreamFailures() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        LlmStructuringService service = new LlmStructuringService(
                builder,
                objectMapper,
                TEST_GEMINI_KEY,
                "gemini-3.5-flash-lite"
        );

        String successPayload = objectMapper.writeValueAsString(
                Map.of("candidates", List.of(
                        Map.of("content", Map.of("parts", List.of(
                                Map.of("text", "[{\"type\":\"MCQ\",\"text\":\"2+2?\",\"options\":{\"A\":\"4\"},\"correctOption\":\"A\",\"assets\":[],\"shared_context\":\"\",\"is_follow_up\":false,\"questionNumber\":\"1\"}]")
                        )))
                ))
        );

        server.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", TEST_GEMINI_KEY))
                .andRespond(withSuccess(successPayload, MediaType.APPLICATION_JSON));

        JsonNode result = service.structureChunk("1. 2+2?", null);
        assertThat(result.isArray()).isTrue();
        assertThat(result).hasSize(1);
        server.verify();

        RestClient.Builder failBuilder = RestClient.builder();
        MockRestServiceServer failServer = MockRestServiceServer.bindTo(failBuilder).build();
        LlmStructuringService failingService = new LlmStructuringService(
                failBuilder,
                objectMapper,
                TEST_GEMINI_KEY,
                "gemini-3.5-flash-lite"
        );

        String upstreamLeakBody = "{\"error\":{\"message\":\"API key not valid: " + TEST_GEMINI_KEY
                + "\", \"secretToken\":\"Bearer top-secret-token\"}}";
        failServer.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", TEST_GEMINI_KEY))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(upstreamLeakBody));

        Logger llmLogger = (Logger) LoggerFactory.getLogger(LlmStructuringService.class);
        ListAppender<ILoggingEvent> logAppender = attachAppender(llmLogger);
        try {
            assertThatThrownBy(() -> failingService.structureChunk("1. Test", null))
                    .isInstanceOf(Exception.class)
                    .satisfies(ex -> {
                        assertThat(ex.getMessage())
                                .isNotBlank()
                                .doesNotContain(TEST_GEMINI_KEY)
                                .doesNotContain("top-secret-token")
                                .doesNotContain("API key not valid");
                    });

            for (ILoggingEvent event : logAppender.list) {
                assertThat(event.getFormattedMessage()).doesNotContain(TEST_GEMINI_KEY);
                assertThat(event.getFormattedMessage()).doesNotContain("top-secret-token");
                assertThat(event.getFormattedMessage()).doesNotContain("API key not valid");
            }
        } finally {
            llmLogger.detachAppender(logAppender);
        }
        failServer.verify();
    }

    @Test
    void asyncIngestionWorker_RedactsApiKeysTokensAndUpstreamBodiesFromPersistedJobAndLogs() throws Exception {
        Subject subject = createSubject();
        IngestionJob job = new IngestionJob();
        job.setSubject(subject);
        job.setStatus(IngestionJobStatus.PENDING);
        job = ingestionJobRepository.save(job);
        createdJobIds.add(job.getId());

        String rawUpstreamBody = "{\"error\":{\"message\":\"Invalid key " + TEST_GEMINI_KEY + "\"}}";
        HttpClientErrorException upstreamError = HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST,
                "Bad Request",
                HttpHeaders.EMPTY,
                ("Request failed at https://generativelanguage.googleapis.com?key=" + TEST_GEMINI_KEY
                        + " x-goog-api-key=" + TEST_GEMINI_KEY
                        + " Authorization: Bearer secret-bearer-999 " + rawUpstreamBody)
                        .getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8
        );

        when(mockedLlmStructuringService.structureChunk(anyString(), nullable(String.class)))
                .thenThrow(upstreamError);

        Logger workerLogger = (Logger) LoggerFactory.getLogger(AsyncIngestionWorker.class);
        ListAppender<ILoggingEvent> logAppender = attachAppender(workerLogger);
        try {
            asyncIngestionWorker.processMarkdown(job.getId(), subject.getId(), "1. Sample question");

            IngestionJob failedJob = awaitTerminalJob(job.getId());
            assertThat(failedJob.getStatus()).isEqualTo(IngestionJobStatus.FAILED);
            assertThat(failedJob.getErrorMessage())
                    .isNotBlank()
                    .doesNotContain(TEST_GEMINI_KEY)
                    .doesNotContain("secret-bearer-999")
                    .doesNotContain("Invalid key")
                    .doesNotContain("?key=" + TEST_GEMINI_KEY);

            for (ILoggingEvent event : logAppender.list) {
                assertThat(event.getFormattedMessage())
                        .doesNotContain(TEST_GEMINI_KEY)
                        .doesNotContain("secret-bearer-999")
                        .doesNotContain("Invalid key");
            }
        } finally {
            workerLogger.detachAppender(logAppender);
        }
    }

    @Test
    void errorSanitizer_RedactsKeysQueryParamsHeadersTokensAndUpstreamPayloads() {
        String raw = "POST https://generativelanguage.googleapis.com/v1beta/models/m:generateContent?key=AIzaSySecret12345678901234567 "
                + "headers x-goog-api-key: hdr-secret-1 x-api-key=docling-hdr-2 "
                + "Authorization: Bearer jwt.token.value "
                + "custom key=custom-secret-99 "
                + "body {\"error\":{\"message\":\"upstream leaked detail\"}}";

        String sanitized = ErrorSanitizer.sanitize(raw, TEST_GEMINI_KEY, "custom-secret-99");

        assertThat(sanitized)
                .doesNotContain("AIzaSySecret12345678901234567")
                .doesNotContain("hdr-secret-1")
                .doesNotContain("docling-hdr-2")
                .doesNotContain("jwt.token.value")
                .doesNotContain("custom-secret-99")
                .doesNotContain("upstream leaked detail")
                .contains("[REDACTED]");
    }

    @Test
    void doclingService_SendsApiKeyHeaderHandlesTimeoutAndSanitizesUpstreamErrors() throws Exception {
        AtomicReference<String> observedHeader = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/parse", exchange -> {
                observedHeader.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
                byte[] ok = "{\"markdown\":\"# Extracted Question 1\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, ok.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(ok);
                }
            });
            server.createContext("/slow/parse", exchange -> {
                try {
                    Thread.sleep(600);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                byte[] ok = "{\"markdown\":\"late\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, ok.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(ok);
                }
            });
            server.createContext("/err/parse", exchange -> {
                byte[] err = ("{\"detail\":\"Upstream crash using " + TEST_DOCLING_KEY + "\"}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(500, err.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(err);
                }
            });
            server.start();
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

            DoclingService doclingService = new DoclingService(
                    RestClient.builder(),
                    baseUrl,
                    TEST_DOCLING_KEY,
                    2000L
            );
            MockMultipartFile pdf = new MockMultipartFile(
                    "file", "exam.pdf", "application/pdf", "%PDF-1.4\n%%EOF".getBytes(StandardCharsets.UTF_8)
            );
            String markdown = doclingService.parsePdf(pdf.getResource());
            assertThat(markdown).isEqualTo("# Extracted Question 1");
            assertThat(observedHeader.get()).isEqualTo(TEST_DOCLING_KEY);

            DoclingService errorDocling = new DoclingService(
                    RestClient.builder(),
                    baseUrl + "/err",
                    TEST_DOCLING_KEY,
                    2000L
            );
            assertThatThrownBy(() -> errorDocling.parsePdf(pdf.getResource()))
                    .isInstanceOf(Exception.class)
                    .satisfies(ex -> assertThat(ex.getMessage())
                            .isNotBlank()
                            .doesNotContain(TEST_DOCLING_KEY)
                            .doesNotContain("Upstream crash"));

            DoclingService slowDocling = new DoclingService(
                    RestClient.builder(),
                    baseUrl + "/slow",
                    TEST_DOCLING_KEY,
                    150L
            );
            assertThatThrownBy(() -> slowDocling.parsePdf(pdf.getResource()))
                    .isInstanceOf(IllegalStateException.class)
                    .satisfies(ex -> assertThat(ex.getMessage())
                            .contains("unavailable or timed out")
                            .doesNotContain(TEST_DOCLING_KEY));

            DoclingService missingKeyDocling = new DoclingService(
                    RestClient.builder(),
                    baseUrl,
                    "",
                    1000L
            );
            assertThatThrownBy(() -> missingKeyDocling.parsePdf(pdf.getResource()))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            server.stop(0);
        }
    }

    private ResultActions performAssetUpload(
            String token,
            MockMultipartFile file) throws Exception {
        return mockMvc.perform(multipart("/api/v1/admin/ingestion/assets")
                .file(file)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private void assertValidAssetUpload(
            String adminToken,
            String originalFilename,
            String contentType,
            byte[] payload,
            String expectedExtension) throws Exception {
        clearInvocations(s3Client);
        MockMultipartFile file = new MockMultipartFile("file", originalFilename, contentType, payload);

        mockMvc.perform(multipart("/api/v1/admin/ingestion/assets")
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url", matchesPattern(
                        "^https://assets\\.studentprep\\.example/cdn/studentprep-assets/diagrams/[0-9a-f\\-]{36}\\" + expectedExtension + "$"
                )));

        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client, atLeastOnce()).putObject(requestCaptor.capture(), any(RequestBody.class));
        PutObjectRequest lastRequest = requestCaptor.getValue();
        assertThat(lastRequest.bucket()).isEqualTo("studentprep-assets");
        assertThat(lastRequest.key()).matches("^diagrams/[0-9a-f\\-]{36}\\" + expectedExtension + "$");
        assertThat(lastRequest.key()).doesNotContain("evil-name");
        assertThat(lastRequest.contentType()).isEqualTo(contentType);
    }

    private String issueSessionToken(String identifier, String role) {
        String jti = UUID.randomUUID().toString();
        String redisKey = SessionRedisKeys.sessionKey(identifier);
        redisTemplate.opsForValue().set(redisKey, jti, Duration.ofMinutes(5));
        createdRedisKeys.add(redisKey);
        return jwtUtil.generateToken(identifier, role, jti);
    }

    private Subject createSubject() {
        Subject subject = new Subject("INGESTION-SEC-" + UUID.randomUUID());
        Subject saved = subjectRepository.save(subject);
        createdSubjectIds.add(saved.getId());
        return saved;
    }

    private IngestionJob awaitTerminalJob(UUID jobId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            IngestionJob current = ingestionJobRepository.findById(jobId).orElseThrow();
            if (current.getStatus() == IngestionJobStatus.FAILED
                    || current.getStatus() == IngestionJobStatus.COMPLETED) {
                return current;
            }
            Thread.sleep(25);
        }
        return ingestionJobRepository.findById(jobId).orElseThrow();
    }

    private static ListAppender<ILoggingEvent> attachAppender(Logger logger) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static byte[] validPngBytes() {
        return new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D};
    }

    private static byte[] validJpegBytes() {
        return new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 0x4A, 0x46};
    }

    private static byte[] validGifBytes() {
        return "GIF89a\u0001\u0000\u0001\u0000".getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] validWebpBytes() {
        return new byte[]{
                'R', 'I', 'F', 'F', 0x24, 0x00, 0x00, 0x00,
                'W', 'E', 'B', 'P', 'V', 'P', '8', ' '
        };
    }
}
