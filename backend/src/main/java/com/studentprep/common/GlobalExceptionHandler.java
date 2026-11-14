package com.studentprep.common;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.regex.Pattern;

/**
 * Global RFC 7807 ProblemDetails exception mapper ensuring validation, domain,
 * authentication, payload, and unexpected errors are serialized safely without leaking internals.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String GENERIC_INTERNAL_ERROR = "An unexpected internal error occurred";
    private static final int MAX_DETAIL_LENGTH = 300;
    private static final Pattern QUERY_SECRET_PATTERN =
            Pattern.compile("([?&](?:key|api_key|apikey|token|secret|password|pin)=)[^&\\s]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEADER_SECRET_PATTERN =
            Pattern.compile("((?:x-goog-api-key|x-api-key|authorization)\\s*[:=]\\s*)\\S+", Pattern.CASE_INSENSITIVE);
    private static final Pattern BEARER_TOKEN_PATTERN =
            Pattern.compile("(Bearer\\s+)[A-Za-z0-9._\\-]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern STACK_FRAME_PATTERN =
            Pattern.compile("\\bat\\s+[\\w.$]+\\([^)]*(?::\\d+|Unknown Source|Native Method)\\)");

    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail handleNoSuchElementException(NoSuchElementException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                sanitizeMessage(ex.getMessage(), "Resource not found")
        );
        setInstanceFromCurrentRequest(problem);
        return problem;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> handleResponseStatusException(ResponseStatusException ex) {
        ProblemDetail problem = buildSanitizedErrorResponseProblem(ex);
        return ResponseEntity.status(ex.getStatusCode()).headers(ex.getHeaders()).body(problem);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgumentException(IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                sanitizeMessage(ex.getMessage(), "Invalid request")
        );
        setInstanceFromCurrentRequest(problem);
        return problem;
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleIllegalStateException(IllegalStateException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                sanitizeMessage(ex.getMessage(), "Request conflicts with current state")
        );
        setInstanceFromCurrentRequest(problem);
        return problem;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolationException(ConstraintViolationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        problem.setTitle("Bad Request");
        setInstanceFromCurrentRequest(problem);

        List<Map<String, String>> errors = new ArrayList<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            String field = violation.getPropertyPath() != null ? violation.getPropertyPath().toString() : "request";
            String message = sanitizeMessage(violation.getMessage(), "Invalid value");
            errors.add(Map.of("field", field, "message", message));
        }
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleAuthenticationException(AuthenticationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Authentication failed");
        problem.setTitle("Unauthorized");
        setInstanceFromCurrentRequest(problem);
        return problem;
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDeniedException(AccessDeniedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Access denied");
        problem.setTitle("Forbidden");
        setInstanceFromCurrentRequest(problem);
        return problem;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolationException(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation handled as 409 Conflict: {}", ex.getMostSpecificCause().getClass().getSimpleName());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "Request conflicts with existing data"
        );
        problem.setTitle("Conflict");
        setInstanceFromCurrentRequest(problem);
        return problem;
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLockingFailure(ObjectOptimisticLockingFailureException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "Resource was modified concurrently; please retry"
        );
        problem.setTitle("Conflict");
        setInstanceFromCurrentRequest(problem);
        return problem;
    }

    @ExceptionHandler(MultipartException.class)
    public ProblemDetail handleMultipartException(MultipartException ex) {
        if (ex instanceof MaxUploadSizeExceededException) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "Uploaded file exceeds the maximum allowed size"
            );
            problem.setTitle("Payload Too Large");
            setInstanceFromCurrentRequest(problem);
            return problem;
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Invalid multipart upload request"
        );
        problem.setTitle("Bad Request");
        setInstanceFromCurrentRequest(problem);
        return problem;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpectedException(Exception ex) {
        log.error("Unhandled exception mapped to 500 ProblemDetail", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                GENERIC_INTERNAL_ERROR
        );
        problem.setTitle("Internal Server Error");
        setInstanceFromCurrentRequest(problem);
        return problem;
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        problem.setTitle("Bad Request");
        setInstanceFromWebRequest(problem, request);

        List<Map<String, String>> errors = new ArrayList<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            errors.add(Map.of(
                    "field", fieldError.getField(),
                    "message", sanitizeMessage(fieldError.getDefaultMessage(), "Invalid value")
            ));
        }
        for (ObjectError globalError : ex.getBindingResult().getGlobalErrors()) {
            errors.add(Map.of(
                    "field", globalError.getObjectName(),
                    "message", sanitizeMessage(globalError.getDefaultMessage(), "Invalid value")
            ));
        }
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        problem.setTitle("Bad Request");
        setInstanceFromWebRequest(problem, request);

        List<Map<String, String>> errors = new ArrayList<>();
        for (ParameterValidationResult result : ex.getAllValidationResults()) {
            String paramName = result.getMethodParameter().getParameterName();
            String field = paramName != null ? paramName : "request";
            for (MessageSourceResolvable resolvable : result.getResolvableErrors()) {
                errors.add(Map.of(
                        "field", field,
                        "message", sanitizeMessage(resolvable.getDefaultMessage(), "Invalid value")
                ));
            }
        }
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Malformed request payload"
        );
        problem.setTitle("Bad Request");
        setInstanceFromWebRequest(problem, request);
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        String propertyName = ex.getPropertyName();
        String detail = propertyName != null && !propertyName.isBlank()
                ? "Invalid value for parameter '" + sanitizeMessage(propertyName, "parameter") + "'"
                : "Invalid parameter format";
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("Bad Request");
        setInstanceFromWebRequest(problem, request);
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
            MissingServletRequestParameterException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        String detail = "Required request parameter '" + ex.getParameterName() + "' is missing";
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("Bad Request");
        setInstanceFromWebRequest(problem, request);
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestPart(
            MissingServletRequestPartException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        String detail = "Required request part '" + ex.getRequestPartName() + "' is missing";
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("Bad Request");
        setInstanceFromWebRequest(problem, request);
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "Uploaded file exceeds the maximum allowed size"
        );
        problem.setTitle("Payload Too Large");
        setInstanceFromWebRequest(problem, request);
        return handleExceptionInternal(ex, problem, headers, HttpStatus.PAYLOAD_TOO_LARGE, request);
    }

    @Override
    protected ResponseEntity<Object> handleErrorResponseException(
            ErrorResponseException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        ProblemDetail problem = buildSanitizedErrorResponseProblem(ex);
        setInstanceFromWebRequest(problem, request);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex,
            Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            setInstanceFromWebRequest(problem, request);
            if (statusCode.is5xxServerError()) {
                problem.setDetail(GENERIC_INTERNAL_ERROR);
            } else if (problem.getDetail() != null) {
                problem.setDetail(sanitizeMessage(problem.getDetail(), defaultDetailForStatus(statusCode)));
            }
        }
        return response;
    }

    private ProblemDetail buildSanitizedErrorResponseProblem(ErrorResponseException ex) {
        HttpStatusCode status = ex.getStatusCode();
        ProblemDetail source = ex.getBody();
        String rawDetail = ex instanceof ResponseStatusException rse && rse.getReason() != null
                ? rse.getReason()
                : source.getDetail();
        String detail = status.is5xxServerError()
                ? GENERIC_INTERNAL_ERROR
                : sanitizeMessage(rawDetail, defaultDetailForStatus(status));

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        if (source.getType() != null) {
            problem.setType(source.getType());
        }
        if (source.getTitle() != null) {
            problem.setTitle(source.getTitle());
        }
        if (source.getInstance() != null) {
            problem.setInstance(source.getInstance());
        } else {
            setInstanceFromCurrentRequest(problem);
        }
        Map<String, Object> properties = source.getProperties();
        if (properties != null) {
            properties.forEach((key, value) -> {
                if (value instanceof String strValue) {
                    problem.setProperty(key, sanitizeMessage(strValue, ""));
                } else if (value != null) {
                    problem.setProperty(key, value);
                }
            });
        }
        return problem;
    }

    private void setInstanceFromCurrentRequest(ProblemDetail problem) {
        if (problem.getInstance() != null) {
            return;
        }
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            String uri = servletAttributes.getRequest().getRequestURI();
            if (uri != null && !uri.isBlank()) {
                problem.setInstance(URI.create(uri));
            }
        }
    }

    private void setInstanceFromWebRequest(ProblemDetail problem, WebRequest request) {
        if (problem.getInstance() != null) {
            return;
        }
        if (request instanceof ServletWebRequest servletWebRequest) {
            String uri = servletWebRequest.getRequest().getRequestURI();
            if (uri != null && !uri.isBlank()) {
                problem.setInstance(URI.create(uri));
                return;
            }
        }
        setInstanceFromCurrentRequest(problem);
    }

    private static String defaultDetailForStatus(HttpStatusCode statusCode) {
        HttpStatus resolved = HttpStatus.resolve(statusCode.value());
        return resolved != null ? resolved.getReasonPhrase() : "Request failed";
    }

    private static String sanitizeMessage(String rawMessage, String fallback) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return fallback;
        }
        String collapsed = rawMessage.replace('\r', ' ').replace('\n', ' ').trim();
        if (looksLikeInternalLeak(collapsed)) {
            return fallback;
        }
        String redacted = QUERY_SECRET_PATTERN.matcher(collapsed).replaceAll("$1[REDACTED]");
        redacted = HEADER_SECRET_PATTERN.matcher(redacted).replaceAll("$1[REDACTED]");
        redacted = BEARER_TOKEN_PATTERN.matcher(redacted).replaceAll("$1[REDACTED]");
        if (redacted.length() > MAX_DETAIL_LENGTH) {
            return redacted.substring(0, MAX_DETAIL_LENGTH) + "...";
        }
        return redacted;
    }

    private static boolean looksLikeInternalLeak(String message) {
        return STACK_FRAME_PATTERN.matcher(message).find()
                || message.contains("Caused by:")
                || message.contains("Exception:")
                || message.contains("PSQLException")
                || message.contains("SQL [")
                || message.contains("org.springframework.")
                || message.contains("org.hibernate.")
                || message.contains("org.postgresql.")
                || message.contains("java.lang.")
                || message.contains("java.sql.")
                || message.contains("jakarta.")
                || message.contains("com.studentprep.");
    }
}
