package com.studentprep.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SecurityConfigTest {

    private WebApplicationContextRunner createRunner() {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        JacksonAutoConfiguration.class,
                        DispatcherServletAutoConfiguration.class,
                        WebMvcAutoConfiguration.class,
                        SecurityAutoConfiguration.class,
                        SecurityFilterAutoConfiguration.class))
                .withUserConfiguration(TestSecurityWiring.class, SecurityConfig.class, StubEndpointsController.class)
                .withPropertyValues("cors.allowed-origins= https://exam.school.edu.ng , http://localhost:5173 ");
    }

    private MockMvc buildMockMvc(WebApplicationContext context) {
        Filter springSecurityFilterChain = context.getBean("springSecurityFilterChain", Filter.class);
        return MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
    }

    @Test
    void passwordEncoderBean_IsPinHashingPasswordEncoderAndRejectsPlaintext() {
        createRunner().run(context -> {
            assertThat(context).hasNotFailed();
            PasswordEncoder encoder = context.getBean(PasswordEncoder.class);
            assertThat(encoder).isInstanceOf(PinHashingPasswordEncoder.class);
            String hash = encoder.encode("123456");
            assertThat(hash).startsWith("$argon2id$");
            assertThat(encoder.matches("123456", hash)).isTrue();
            assertThat(encoder.matches("123456", "123456")).isFalse();
        });
    }

    @Test
    void corsConfiguration_RejectsWildcardAndBlankOrigins() {
        createRunner()
                .withPropertyValues("cors.allowed-origins=*")
                .run(context -> assertThat(context)
                        .getFailure()
                        .hasRootCauseInstanceOf(IllegalArgumentException.class));

        createRunner()
                .withPropertyValues("cors.allowed-origins=https://exam.school.edu.ng,*")
                .run(context -> assertThat(context)
                        .getFailure()
                        .hasRootCauseInstanceOf(IllegalArgumentException.class));

        createRunner()
                .withPropertyValues("cors.allowed-origins=   ")
                .run(context -> assertThat(context)
                        .getFailure()
                        .hasRootCauseInstanceOf(IllegalArgumentException.class));
    }

    @Test
    void corsPreflight_AllowsConfiguredTrimmedOriginAndRejectsUnknownOrigin() {
        createRunner().run(context -> {
            MockMvc mockMvc = buildMockMvc(context);

            mockMvc.perform(options("/api/v1/auth/login")
                            .header(HttpHeaders.ORIGIN, "https://exam.school.edu.ng")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://exam.school.edu.ng"))
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));

            mockMvc.perform(options("/api/v1/auth/login")
                            .header(HttpHeaders.ORIGIN, "https://evil.example")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        });
    }

    @Test
    void publicEndpoints_AreAccessibleUnauthenticatedWhileProtectedEndpointsReturn401ProblemDetail() {
        createRunner().run(context -> {
            MockMvc mockMvc = buildMockMvc(context);

            mockMvc.perform(post("/api/v1/auth/login")).andExpect(status().isOk());
            mockMvc.perform(post("/api/v1/auth/refresh")).andExpect(status().isOk());
            mockMvc.perform(post("/api/v1/auth/logout")).andExpect(status().isOk());
            mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());

            assertUnauthorizedProblem(mockMvc, get("/api/v1/auth/me"), "/api/v1/auth/me");
            assertUnauthorizedProblem(mockMvc, get("/api/v1/subjects"), "/api/v1/subjects");
            assertUnauthorizedProblem(mockMvc, get("/api/v1/exams/active/session"), "/api/v1/exams/active/session");
            assertUnauthorizedProblem(mockMvc, get("/api/v1/students/me/results"), "/api/v1/students/me/results");
            assertUnauthorizedProblem(mockMvc, get("/api/v1/admin/students"), "/api/v1/admin/students");
        });
    }

    @Test
    void studentRole_AllowsStudentAndExamRoutesAndBlocksAdminRoutesWith403ProblemDetail() {
        createRunner().run(context -> {
            MockMvc mockMvc = buildMockMvc(context);

            mockMvc.perform(get("/api/v1/subjects").header(HttpHeaders.AUTHORIZATION, "Bearer token-student"))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/exams/active/session").header(HttpHeaders.AUTHORIZATION, "Bearer token-student"))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/students/me/results").header(HttpHeaders.AUTHORIZATION, "Bearer token-student"))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/student/results").header(HttpHeaders.AUTHORIZATION, "Bearer token-student"))
                    .andExpect(status().isOk());

            assertForbiddenProblem(mockMvc, get("/api/v1/admin/students"), "token-student", "/api/v1/admin/students");
            assertForbiddenProblem(
                    mockMvc,
                    get("/api/v1/admin/analytics/dashboard"),
                    "token-student",
                    "/api/v1/admin/analytics/dashboard");
        });
    }

    @Test
    void supervisorRole_AllowsReadOnlyMonitoringAndBlocksWritesExportSlipsAndIngestion() {
        createRunner().run(context -> {
            MockMvc mockMvc = buildMockMvc(context);

            mockMvc.perform(get("/api/v1/subjects").header(HttpHeaders.AUTHORIZATION, "Bearer token-supervisor"))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/admin/analytics/dashboard")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer token-supervisor"))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/admin/subjects")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer token-supervisor"))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/admin/subjects/sub-1")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer token-supervisor"))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/admin/questions")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer token-supervisor"))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/admin/students")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer token-supervisor"))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/admin/students/stu-1")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer token-supervisor"))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/admin/students/stu-1/results")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer token-supervisor"))
                    .andExpect(status().isOk());

            assertForbiddenProblem(
                    mockMvc,
                    get("/api/v1/admin/students/export"),
                    "token-supervisor",
                    "/api/v1/admin/students/export");
            assertForbiddenProblem(
                    mockMvc,
                    get("/api/v1/admin/students/stu-1/slip"),
                    "token-supervisor",
                    "/api/v1/admin/students/stu-1/slip");
            assertForbiddenProblem(
                    mockMvc,
                    get("/api/v1/admin/results/res-1/slip"),
                    "token-supervisor",
                    "/api/v1/admin/results/res-1/slip");
            assertForbiddenProblem(
                    mockMvc,
                    get("/api/v1/admin/students/stu-1/results/res-1/slip"),
                    "token-supervisor",
                    "/api/v1/admin/students/stu-1/results/res-1/slip");
            assertForbiddenProblem(
                    mockMvc,
                    post("/api/v1/admin/students"),
                    "token-supervisor",
                    "/api/v1/admin/students");
            assertForbiddenProblem(
                    mockMvc,
                    put("/api/v1/admin/students/stu-1"),
                    "token-supervisor",
                    "/api/v1/admin/students/stu-1");
            assertForbiddenProblem(
                    mockMvc,
                    delete("/api/v1/admin/students/stu-1"),
                    "token-supervisor",
                    "/api/v1/admin/students/stu-1");
            assertForbiddenProblem(
                    mockMvc,
                    post("/api/v1/admin/students/stu-1/reset-pin"),
                    "token-supervisor",
                    "/api/v1/admin/students/stu-1/reset-pin");
            assertForbiddenProblem(
                    mockMvc,
                    post("/api/v1/admin/students/stu-1/unlock-session"),
                    "token-supervisor",
                    "/api/v1/admin/students/stu-1/unlock-session");
            assertForbiddenProblem(
                    mockMvc,
                    post("/api/v1/admin/questions/drafts/bulk-approve"),
                    "token-supervisor",
                    "/api/v1/admin/questions/drafts/bulk-approve");
            assertForbiddenProblem(
                    mockMvc,
                    post("/api/v1/admin/subjects"),
                    "token-supervisor",
                    "/api/v1/admin/subjects");
            assertForbiddenProblem(
                    mockMvc,
                    post("/api/v1/admin/ingestion/pdf"),
                    "token-supervisor",
                    "/api/v1/admin/ingestion/pdf");
            assertForbiddenProblem(
                    mockMvc,
                    get("/api/v1/admin/ingestion/jobs/job-1"),
                    "token-supervisor",
                    "/api/v1/admin/ingestion/jobs/job-1");
            assertForbiddenProblem(
                    mockMvc,
                    get("/api/v1/exams/active/session"),
                    "token-supervisor",
                    "/api/v1/exams/active/session");
        });
    }

    @Test
    void adminRole_AllowsMutatingAdminExportSlipsAndIngestionAndBlocksStudentRoutes() {
        createRunner().run(context -> {
            MockMvc mockMvc = buildMockMvc(context);

            mockMvc.perform(get("/api/v1/subjects").header(HttpHeaders.AUTHORIZATION, "Bearer token-admin"))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/admin/students/export")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer token-admin"))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/admin/students/stu-1/slip")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer token-admin"))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/admin/results/res-1/slip")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer token-admin"))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/admin/students/stu-1/results/res-1/slip")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer token-admin"))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/api/v1/admin/students")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer token-admin"))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/api/v1/admin/students/stu-1/unlock-session")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer token-admin"))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/api/v1/admin/ingestion/pdf")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer token-admin"))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/admin/ingestion/jobs/job-1")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer token-admin"))
                    .andExpect(status().isOk());

            assertForbiddenProblem(
                    mockMvc,
                    get("/api/v1/exams/active/session"),
                    "token-admin",
                    "/api/v1/exams/active/session");
            assertForbiddenProblem(
                    mockMvc,
                    get("/api/v1/students/me/results"),
                    "token-admin",
                    "/api/v1/students/me/results");
            assertForbiddenProblem(
                    mockMvc,
                    get("/api/v1/student/results"),
                    "token-admin",
                    "/api/v1/student/results");
        });
    }

    private void assertUnauthorizedProblem(
            MockMvc mockMvc,
            MockHttpServletRequestBuilder requestBuilder,
            String expectedInstance) throws Exception {
        mockMvc.perform(requestBuilder)
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.detail").value("Authentication required or token expired"))
                .andExpect(jsonPath("$.instance").value(expectedInstance));
    }

    private void assertForbiddenProblem(
            MockMvc mockMvc,
            MockHttpServletRequestBuilder requestBuilder,
            String token,
            String expectedInstance) throws Exception {
        mockMvc.perform(requestBuilder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Forbidden"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.detail").value("Access denied"))
                .andExpect(jsonPath("$.instance").value(expectedInstance));
    }

    @Configuration(proxyBeanMethods = false)
    static class TestSecurityWiring {

        @Bean
        @SuppressWarnings("unchecked")
        JwtAuthenticationFilter jwtAuthenticationFilter() {
            JwtUtil jwtUtil = mock(JwtUtil.class);
            stubUser(jwtUtil, "token-student", "STUDENT-001", "jti-student", "ROLE_STUDENT");
            stubUser(jwtUtil, "token-supervisor", "SUP-001", "jti-supervisor", "ROLE_SUPERVISOR");
            stubUser(jwtUtil, "token-admin", "ADMIN-001", "jti-admin", "ROLE_ADMIN");

            StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
            ValueOperations<String, String> valueOps = mock(ValueOperations.class);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get("session:STUDENT-001")).thenReturn("jti-student");
            when(valueOps.get("session:SUP-001")).thenReturn("jti-supervisor");
            when(valueOps.get("session:ADMIN-001")).thenReturn("jti-admin");

            return new JwtAuthenticationFilter(jwtUtil, redisTemplate, new ObjectMapper());
        }

        private static void stubUser(
                JwtUtil jwtUtil,
                String token,
                String identifier,
                String jti,
                String role) {
            when(jwtUtil.isAccessTokenValid(token)).thenReturn(true);
            when(jwtUtil.isTokenValid(token)).thenReturn(true);
            when(jwtUtil.extractIdentifier(token)).thenReturn(identifier);
            when(jwtUtil.extractJti(token)).thenReturn(jti);
            when(jwtUtil.extractRole(token)).thenReturn(role);
        }
    }

    @RestController
    static class StubEndpointsController {

        @PostMapping({
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/logout",
            "/api/v1/admin/students",
            "/api/v1/admin/students/{id}/reset-pin",
            "/api/v1/admin/students/{id}/unlock-session",
            "/api/v1/admin/questions/drafts/bulk-approve",
            "/api/v1/admin/subjects",
            "/api/v1/admin/ingestion/pdf"
        })
        String postOk() {
            return "ok";
        }

        @PutMapping("/api/v1/admin/students/{id}")
        String putOk() {
            return "ok";
        }

        @DeleteMapping("/api/v1/admin/students/{id}")
        String deleteOk() {
            return "ok";
        }

        @GetMapping({
            "/actuator/health",
            "/api/v1/auth/me",
            "/api/v1/subjects",
            "/api/v1/exams/active/session",
            "/api/v1/students/me/results",
            "/api/v1/student/results",
            "/api/v1/admin/analytics/dashboard",
            "/api/v1/admin/subjects",
            "/api/v1/admin/subjects/{id}",
            "/api/v1/admin/questions",
            "/api/v1/admin/students",
            "/api/v1/admin/students/export",
            "/api/v1/admin/students/{id}",
            "/api/v1/admin/students/{id}/slip",
            "/api/v1/admin/students/{id}/results",
            "/api/v1/admin/results/{resultId}/slip",
            "/api/v1/admin/students/{id}/results/{resultId}/slip",
            "/api/v1/admin/ingestion/jobs/{jobId}"
        })
        String getOk() {
            return "ok";
        }
    }
}
