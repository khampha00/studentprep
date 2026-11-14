package com.studentprep.common;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

import java.util.NoSuchElementException;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void validationException_EmitsProblemDetailWithFieldErrors() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.detail").value("Validation failed"))
                .andExpect(jsonPath("$.instance").value("/test/validate"))
                .andExpect(jsonPath("$.errors[0].field").value("identifier"));
    }

    @Test
    void domainAndStatusExceptions_EmitRfc7807ProblemDetailsAndPreserveCodeAndHeaders() throws Exception {
        mockMvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Student missing"));

        mockMvc.perform(get("/test/bad-arg"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Invalid input"));

        mockMvc.perform(get("/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("Already active"));

        mockMvc.perform(get("/test/pin-reset"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("PIN_RESET_REQUIRED"))
                .andExpect(jsonPath("$.detail").value("Your PIN has been invalidated and must be reset by an administrator."));

        mockMvc.perform(get("/test/rate-limited"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "60"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.detail").value("Too many attempts"));
    }

    @Test
    void authPayloadAndUnexpectedExceptions_SanitizeInternalDetails() throws Exception {
        mockMvc.perform(get("/test/auth-fail"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.detail").value("Authentication failed"));

        mockMvc.perform(get("/test/access-denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.detail").value("Access denied"));

        mockMvc.perform(get("/test/max-upload"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.status").value(413))
                .andExpect(jsonPath("$.detail").value("Uploaded file exceeds the maximum allowed size"));

        mockMvc.perform(get("/test/db-conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("Request conflicts with existing data"))
                .andExpect(jsonPath("$.detail", not(containsString("users_pkey"))));

        mockMvc.perform(get("/test/optimistic"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        mockMvc.perform(get("/test/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").value("An unexpected internal error occurred"))
                .andExpect(jsonPath("$.detail", not(containsString("AIzaSecret"))))
                .andExpect(jsonPath("$.detail", not(containsString("NullPointerException"))));

        mockMvc.perform(get("/test/secret-leak-arg"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail", not(containsString("AIzaSuperSecret"))))
                .andExpect(jsonPath("$.detail").value(containsString("[REDACTED]")));

        mockMvc.perform(get("/test/uuid/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail", not(containsString("java.util.UUID"))));

        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Malformed request payload"));

        mockMvc.perform(get("/test/rse-500"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.detail").value("An unexpected internal error occurred"))
                .andExpect(jsonPath("$.detail", not(containsString("AIzaUpstream"))));
    }

    record SampleRequest(@NotBlank(message = "Identifier is required") String identifier) {
    }

    @RestController
    @RequestMapping("/test")
    static class TestController {

        @PostMapping("/validate")
        String validate(@Valid @RequestBody SampleRequest request) {
            return request.identifier();
        }

        @GetMapping("/not-found")
        String notFound() {
            throw new NoSuchElementException("Student missing");
        }

        @GetMapping("/bad-arg")
        String badArg() {
            throw new IllegalArgumentException("Invalid input");
        }

        @GetMapping("/conflict")
        String conflict() {
            throw new IllegalStateException("Already active");
        }

        @GetMapping("/pin-reset")
        String pinReset() {
            ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                    HttpStatus.FORBIDDEN,
                    "Your PIN has been invalidated and must be reset by an administrator."
            );
            detail.setProperty("code", "PIN_RESET_REQUIRED");
            throw new ErrorResponseException(HttpStatus.FORBIDDEN, detail, null);
        }

        @GetMapping("/rate-limited")
        String rateLimited() {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts") {
                @Override
                public HttpHeaders getHeaders() {
                    HttpHeaders headers = new HttpHeaders();
                    headers.add(HttpHeaders.RETRY_AFTER, "60");
                    return headers;
                }
            };
        }

        @GetMapping("/auth-fail")
        String authFail() {
            throw new BadCredentialsException("Bad pin for ADMIN-001");
        }

        @GetMapping("/access-denied")
        String accessDenied() {
            throw new AccessDeniedException("Requires ROLE_ADMIN");
        }

        @GetMapping("/max-upload")
        String maxUpload() {
            throw new MaxUploadSizeExceededException(1048576L);
        }

        @GetMapping("/db-conflict")
        String dbConflict() {
            throw new DataIntegrityViolationException("duplicate key value violates unique constraint \"users_pkey\"");
        }

        @GetMapping("/optimistic")
        String optimistic() {
            throw new ObjectOptimisticLockingFailureException("com.studentprep.exam.ExamSession", UUID.randomUUID());
        }

        @GetMapping("/unexpected")
        String unexpected() {
            throw new NullPointerException("Upstream failure https://api.example.com?key=AIzaSecret");
        }

        @GetMapping("/secret-leak-arg")
        String secretLeakArg() {
            throw new IllegalArgumentException("Failed calling https://generativelanguage.googleapis.com?key=AIzaSuperSecret");
        }

        @GetMapping("/uuid/{id}")
        String parseUuid(@PathVariable UUID id) {
            return id.toString();
        }

        @GetMapping("/rse-500")
        String rse500() {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Upstream https://host?key=AIzaUpstream failed");
        }
    }
}
