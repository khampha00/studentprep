package com.studentprep.ingestion;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studentprep.ingestion.job.IngestionJob;
import com.studentprep.ingestion.job.IngestionJobRepository;
import com.studentprep.ingestion.job.IngestionJobStatus;
import com.studentprep.questionbank.Question;
import com.studentprep.questionbank.QuestionContext;
import com.studentprep.questionbank.QuestionContextRepository;
import com.studentprep.questionbank.QuestionRepository;
import com.studentprep.questionbank.QuestionStatus;
import com.studentprep.questionbank.Subject;
import com.studentprep.questionbank.SubjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientResponseException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Processes uploaded PDFs and extracted exam markdown asynchronously on a bounded executor,
 * tracking job-scoped passage contexts and recovering stranded jobs on startup.
 */
@Service
public class AsyncIngestionWorker {

    private static final Logger log = LoggerFactory.getLogger(AsyncIngestionWorker.class);
    private static final long DEFAULT_RETRY_DELAY_MS = 3000L;
    private static final long MAX_TRANSIENT_BACKOFF_MS = 60_000L;
    private static final long RATE_LIMIT_DELAY_SECONDS = 25L;
    private static final int MAX_RETRIES = 3;
    private static final int MAX_TRANSIENT_UNAVAILABLE_RETRIES = 6;
    private static final int MAX_RATE_LIMIT_WAITS = 10;
    private static final int DEFAULT_CHUNK_SIZE = 4000;
    private static final String DEFAULT_PDF_FILENAME = "upload.pdf";
    private static final String RESTART_RECOVERY_ERROR = "Server restarted before ingestion completed";
    private static final Pattern QUESTION_SPLIT_PATTERN = Pattern.compile("\\n(Question\\s+|Q)?\\d+[\\.\\)]\\s");
    private static final Pattern GAP_FILL_CONTINUATION_PATTERN =
            Pattern.compile("^fill\\s+in\\s+gap\\b.*", Pattern.CASE_INSENSITIVE);
    private static final TypeReference<Map<String, Object>> CONTENT_MAP_TYPE = new TypeReference<>() {};

    private final DoclingService doclingService;
    private final LlmStructuringService llmStructuringService;
    private final IngestionJobRepository jobRepository;
    private final QuestionRepository questionRepository;
    private final SubjectRepository subjectRepository;
    private final QuestionContextRepository questionContextRepository;
    private final ObjectMapper objectMapper;
    private final ErrorSanitizer errorSanitizer;
    private final TransactionTemplate transactionTemplate;
    private final long retryDelayMs;

    @Autowired
    public AsyncIngestionWorker(
            DoclingService doclingService,
            LlmStructuringService llmStructuringService,
            IngestionJobRepository jobRepository,
            QuestionRepository questionRepository,
            SubjectRepository subjectRepository,
            QuestionContextRepository questionContextRepository,
            ObjectMapper objectMapper,
            ErrorSanitizer errorSanitizer,
            PlatformTransactionManager transactionManager,
            @Value("${ingestion.retry-delay-ms:3000}") Long retryDelayMs) {
        this.doclingService = doclingService;
        this.llmStructuringService = llmStructuringService;
        this.jobRepository = jobRepository;
        this.questionRepository = questionRepository;
        this.subjectRepository = subjectRepository;
        this.questionContextRepository = questionContextRepository;
        this.objectMapper = objectMapper;
        this.errorSanitizer = errorSanitizer != null ? errorSanitizer : new ErrorSanitizer("", "", "");
        this.transactionTemplate = transactionManager != null ? new TransactionTemplate(transactionManager) : null;
        this.retryDelayMs = (retryDelayMs != null && retryDelayMs > 0) ? retryDelayMs : DEFAULT_RETRY_DELAY_MS;
    }

    public AsyncIngestionWorker(
            LlmStructuringService llmStructuringService,
            IngestionJobRepository jobRepository,
            QuestionRepository questionRepository,
            SubjectRepository subjectRepository,
            QuestionContextRepository questionContextRepository,
            ObjectMapper objectMapper) {
        this(
                null,
                llmStructuringService,
                jobRepository,
                questionRepository,
                subjectRepository,
                questionContextRepository,
                objectMapper,
                new ErrorSanitizer("", "", ""),
                null,
                DEFAULT_RETRY_DELAY_MS
        );
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverStrandedJobsOnStartup() {
        runInTransaction(() -> {
            List<IngestionJob> strandedJobs = jobRepository.findByStatusIn(
                    List.of(IngestionJobStatus.PENDING, IngestionJobStatus.PROCESSING)
            );
            if (strandedJobs == null || strandedJobs.isEmpty()) {
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            for (IngestionJob job : strandedJobs) {
                job.setStatus(IngestionJobStatus.FAILED);
                job.setErrorMessage(RESTART_RECOVERY_ERROR);
                job.setUpdatedAt(now);
            }
            jobRepository.saveAll(strandedJobs);
            log.warn("Marked {} stranded ingestion job(s) as FAILED after server restart", strandedJobs.size());
        });
    }

    @Async("ingestionTaskExecutor")
    public void processPdf(UUID jobId, UUID subjectId, byte[] pdfBytes) {
        processPdfInternal(jobId, subjectId, pdfBytes, DEFAULT_PDF_FILENAME);
    }

    @Async("ingestionTaskExecutor")
    public void processPdf(UUID jobId, UUID subjectId, byte[] pdfBytes, String originalFilename) {
        processPdfInternal(jobId, subjectId, pdfBytes, sanitizePdfFilename(originalFilename));
    }

    @Async("ingestionTaskExecutor")
    public void processMarkdown(UUID jobId, UUID subjectId, String markdown) {
        runMarkdownPipeline(jobId, subjectId, markdown, false);
    }

    private void processPdfInternal(UUID jobId, UUID subjectId, byte[] pdfBytes, String filename) {
        boolean ready = Boolean.TRUE.equals(executeInTransaction(() -> {
            IngestionJob job = jobRepository.findById(jobId).orElse(null);
            if (job == null) {
                return false;
            }
            Subject subject = subjectRepository.findById(subjectId).orElse(null);
            if (subject == null) {
                failManagedJob(job, "Subject not found");
                return false;
            }
            if (pdfBytes == null || pdfBytes.length == 0) {
                failManagedJob(job, "Uploaded PDF file must not be empty");
                return false;
            }
            job.setStatus(IngestionJobStatus.PROCESSING);
            job.setUpdatedAt(LocalDateTime.now());
            jobRepository.save(job);
            return true;
        }));

        if (!ready) {
            return;
        }

        String markdown;
        try {
            if (doclingService == null) {
                throw new IllegalStateException("Document parser service is not configured.");
            }
            Resource pdfResource = new NamedByteArrayResource(pdfBytes, filename);
            markdown = doclingService.parsePdf(pdfResource);
        } catch (Exception ex) {
            String sanitizedError = errorSanitizer.sanitizeException(ex);
            log.error("PDF parsing failed for job {}: {}", jobId, sanitizedError);
            markJobFailed(jobId, sanitizedError);
            return;
        }

        runMarkdownPipeline(jobId, subjectId, markdown, true);
    }

    private void runMarkdownPipeline(UUID jobId, UUID subjectId, String markdown, boolean alreadyProcessing) {
        List<String> chunks;
        try {
            chunks = executeInTransaction(() -> {
                IngestionJob job = jobRepository.findById(jobId).orElse(null);
                if (job == null) {
                    return null;
                }
                Subject subject = subjectRepository.findById(subjectId).orElse(null);
                if (subject == null) {
                    failManagedJob(job, "Subject not found");
                    return null;
                }
                List<String> splitChunks = splitMarkdownIntoChunks(markdown);
                if (!alreadyProcessing) {
                    job.setStatus(IngestionJobStatus.PROCESSING);
                }
                job.setTotalChunks(splitChunks.size());
                job.setUpdatedAt(LocalDateTime.now());
                jobRepository.save(job);
                return splitChunks;
            });
        } catch (Exception ex) {
            String sanitizedError = errorSanitizer.sanitizeException(ex);
            log.error("Failed to initialize markdown ingestion for job {}: {}", jobId, sanitizedError);
            markJobFailed(jobId, sanitizedError);
            return;
        }

        if (chunks == null) {
            return;
        }

        try {
            ContextState contextState = ContextState.EMPTY;
            for (int index = 0; index < chunks.size(); index++) {
                String chunk = chunks.get(index);
                ChunkOutcome outcome = structureChunkWithRetries(jobId, index, chunk, contextState.passage());
                if (outcome.failed()) {
                    return;
                }
                final int chunkIndex = index;
                final UUID activeContextId = contextState.contextId();
                final JsonNode questionsNode = outcome.questions();
                contextState = executeInTransaction(() ->
                        saveChunkQuestions(jobId, subjectId, chunkIndex, questionsNode, activeContextId)
                );
            }

            runInTransaction(() -> {
                postProcessContextGroupsInternal(jobId, null);
                jobRepository.findById(jobId).ifPresent(job -> {
                    job.setStatus(IngestionJobStatus.COMPLETED);
                    job.setUpdatedAt(LocalDateTime.now());
                    jobRepository.save(job);
                });
            });
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Ingestion worker interrupted for job {}", jobId);
            markJobFailed(jobId, "Ingestion interrupted before completion");
        } catch (Exception ex) {
            String sanitizedError = errorSanitizer.sanitizeException(ex);
            log.error("Failed during ingestion pipeline for job {}: {}", jobId, sanitizedError);
            markJobFailed(jobId, sanitizedError);
        }
    }

    private ChunkOutcome structureChunkWithRetries(
            UUID jobId,
            int chunkIndex,
            String chunk,
            String activeContextPassage) throws InterruptedException {
        String warning = buildContinuationWarning(activeContextPassage);
        int retryCount = 0;
        int rateLimitCount = 0;
        int unavailableCount = 0;

        while (true) {
            try {
                return ChunkOutcome.success(llmStructuringService.structureChunk(chunk, warning));
            } catch (Exception ex) {
                String sanitizedError = errorSanitizer.sanitizeException(ex);
                if (isRateLimitFailure(ex, sanitizedError)) {
                    if (rateLimitCount + 1 >= MAX_RATE_LIMIT_WAITS) {
                        log.warn("Gemini API rate limit exceeded for job {} after {} waits",
                                jobId, MAX_RATE_LIMIT_WAITS);
                        markJobFailed(jobId, "Gemini API rate limit exceeded after 10 waits. Please try again later.");
                        return ChunkOutcome.FAILED;
                    }
                    rateLimitCount++;
                    log.warn("Hit 429 rate limit on job {}, waiting {}s (wait {}/{})",
                            jobId, RATE_LIMIT_DELAY_SECONDS, rateLimitCount, MAX_RATE_LIMIT_WAITS);
                    Thread.sleep(RATE_LIMIT_DELAY_SECONDS * 1000L);
                } else if (isTransientUnavailableFailure(ex, sanitizedError)) {
                    if (unavailableCount + 1 >= MAX_TRANSIENT_UNAVAILABLE_RETRIES) {
                        log.error("LLM extraction failed for job {} after {} temporary 503 retries: {}",
                                jobId, MAX_TRANSIENT_UNAVAILABLE_RETRIES, sanitizedError);
                        markJobFailed(jobId, errorSanitizer.sanitizeMessage(
                                "LLM Extraction failed after " + MAX_TRANSIENT_UNAVAILABLE_RETRIES
                                        + " temporary unavailable retries: " + sanitizedError
                        ));
                        return ChunkOutcome.FAILED;
                    }
                    long backoffMs = calculateTransientUnavailableBackoffMs(unavailableCount);
                    unavailableCount++;
                    log.warn("Gemini service temporarily unavailable (503) on chunk {} for job {} (attempt {}/{}), retrying in {}ms: {}",
                            chunkIndex, jobId, unavailableCount, MAX_TRANSIENT_UNAVAILABLE_RETRIES, backoffMs, sanitizedError);
                    Thread.sleep(backoffMs);
                } else {
                    if (retryCount + 1 >= MAX_RETRIES) {
                        log.error("LLM extraction failed for job {} after {} retries: {}",
                                jobId, MAX_RETRIES, sanitizedError);
                        markJobFailed(jobId, errorSanitizer.sanitizeMessage(
                                "LLM Extraction failed after 3 retries: " + sanitizedError
                        ));
                        return ChunkOutcome.FAILED;
                    }
                    retryCount++;
                    log.warn("Failed to process chunk {} for job {} (attempt {}/{}): {}",
                            chunkIndex, jobId, retryCount, MAX_RETRIES, sanitizedError);
                    Thread.sleep(retryDelayMs);
                }
            }
        }
    }

    private ContextState saveChunkQuestions(
            UUID jobId,
            UUID subjectId,
            int chunkIndex,
            JsonNode structuredQuestions,
            UUID activeContextId) {
        Subject subject = subjectRepository.findById(subjectId)
                .orElseThrow(() -> new IllegalStateException("Subject not found"));
        QuestionContext activeContext = activeContextId != null
                ? questionContextRepository.findById(activeContextId).orElse(null)
                : null;

        if (structuredQuestions != null && structuredQuestions.isArray()) {
            for (JsonNode qNode : structuredQuestions) {
                if (qNode == null || !qNode.isObject()) {
                    continue;
                }
                Map<String, Object> contentMap = objectMapper.convertValue(qNode, CONTENT_MAP_TYPE);
                if (contentMap == null) {
                    continue;
                }

                Question question = new Question();
                question.setStatus(QuestionStatus.DRAFT);
                question.setSubject(subject);
                question.setIngestionJobId(jobId);
                activeContext = applyContextResolution(question, subject, activeContext, contentMap);
                question.setContent(contentMap);
                questionRepository.save(question);
            }
        }

        jobRepository.findById(jobId).ifPresent(job -> {
            job.setProcessedChunks(chunkIndex + 1);
            job.setUpdatedAt(LocalDateTime.now());
            jobRepository.save(job);
        });

        return activeContext != null
                ? new ContextState(activeContext.getId(), activeContext.getPassage())
                : ContextState.EMPTY;
    }

    private static String buildContinuationWarning(String activeContextPassage) {
        if (activeContextPassage == null || activeContextPassage.isBlank()) {
            return null;
        }
        return "Note: The previous chunk ended inside a shared context group for this specific passage:\n\n\""
                + activeContextPassage
                + "\"\n\nIf the first questions in this chunk belong to that passage, DO NOT extract the passage again, "
                + "just set `is_follow_up: true` for those questions.";
    }

    private static List<String> splitMarkdownIntoChunks(String markdown) {
        String safeMarkdown = markdown != null ? markdown : "";
        List<Integer> splitPoints = new ArrayList<>();
        splitPoints.add(0);
        Matcher spMatcher = QUESTION_SPLIT_PATTERN.matcher(safeMarkdown);
        while (spMatcher.find()) {
            splitPoints.add(spMatcher.start());
        }
        splitPoints.add(safeMarkdown.length());

        List<String> chunks = new ArrayList<>();
        int currentStart = 0;
        while (currentStart < safeMarkdown.length()) {
            int targetEnd = Math.min(currentStart + DEFAULT_CHUNK_SIZE, safeMarkdown.length());
            int bestSplit = currentStart;

            for (int sp : splitPoints) {
                if (sp > currentStart && sp <= targetEnd) {
                    bestSplit = sp;
                }
            }

            if (bestSplit == currentStart) {
                for (int sp : splitPoints) {
                    if (sp > targetEnd) {
                        bestSplit = sp;
                        break;
                    }
                }
            }

            if (bestSplit <= currentStart) {
                bestSplit = targetEnd;
            }

            chunks.add(safeMarkdown.substring(currentStart, bestSplit));
            currentStart = bestSplit;
        }
        return chunks;
    }

    private long calculateTransientUnavailableBackoffMs(int attemptIndex) {
        long baseDelayMs = Math.max(0L, retryDelayMs);
        long multiplier = 1L << Math.min(attemptIndex, 4);
        return Math.min(MAX_TRANSIENT_BACKOFF_MS, baseDelayMs * multiplier);
    }

    private static boolean isRateLimitFailure(Exception ex, String sanitizedError) {
        return isHttpStatusFailure(ex, sanitizedError, 429, "429");
    }

    private static boolean isTransientUnavailableFailure(Exception ex, String sanitizedError) {
        return isHttpStatusFailure(ex, sanitizedError, 503, "HTTP 503")
                || (sanitizedError != null && sanitizedError.contains("503 Service Unavailable"));
    }

    private static boolean isHttpStatusFailure(
            Exception ex,
            String sanitizedError,
            int statusCode,
            String messageToken) {
        if (ex instanceof RestClientResponseException responseException
                && responseException.getStatusCode().value() == statusCode) {
            return true;
        }
        return sanitizedError != null && sanitizedError.contains(messageToken);
    }

    /**
     * Scopes context post-processing strictly to the questions produced by a single ingestion job,
     * respecting {@code is_follow_up} termination and unambiguous gap-fill continuations.
     */
    @Transactional
    public void postProcessContextGroups(UUID jobId) {
        postProcessContextGroupsInternal(jobId, null);
    }

    /**
     * Fallback subject-level post-processing used only when no job identifier is provided (e.g. unit tests).
     */
    @Transactional
    public void postProcessContextGroups(Subject subject) {
        postProcessContextGroupsInternal(null, subject);
    }

    private void postProcessContextGroupsInternal(UUID jobId, Subject fallbackSubject) {
        List<Question> questions;
        if (jobId != null) {
            questions = questionRepository.findByIngestionJobIdOrderByCreatedAtAsc(jobId);
        } else if (fallbackSubject != null) {
            questions = questionRepository.findBySubjectOrderByCreatedAtAsc(fallbackSubject);
        } else {
            return;
        }

        if (questions == null || questions.isEmpty()) {
            return;
        }

        QuestionContext activeContext = null;
        for (Question q : questions) {
            if (q.getContext() != null) {
                activeContext = q.getContext();
                continue;
            }

            Map<String, Object> content = q.getContent();
            if (content == null) {
                continue;
            }

            Subject ownerSubject = q.getSubject() != null ? q.getSubject() : fallbackSubject;
            activeContext = applyContextResolution(q, ownerSubject, activeContext, content);
            if (q.getContext() != null) {
                questionRepository.save(q);
                log.debug("[PostProcess] Linked question {} to context {}",
                        q.getId(), q.getContext().getId());
            }
        }
    }

    private QuestionContext applyContextResolution(
            Question question,
            Subject ownerSubject,
            QuestionContext activeContext,
            Map<String, Object> content) {
        String sharedContextText = extractSharedContext(content);
        if (!sharedContextText.isEmpty()) {
            QuestionContext resolved = findOrCreateContext(ownerSubject, activeContext, sharedContextText);
            question.setContext(resolved);
            return resolved;
        }

        Boolean isFollowUp = extractFollowUpFlag(content);
        if (Boolean.TRUE.equals(isFollowUp)) {
            if (activeContext != null) {
                question.setContext(activeContext);
            }
            return activeContext;
        }

        if (Boolean.FALSE.equals(isFollowUp)) {
            return null;
        }

        if (activeContext != null && isGapFillContinuation(content)) {
            question.setContext(activeContext);
            return activeContext;
        }

        return null;
    }

    private QuestionContext findOrCreateContext(
            Subject ownerSubject,
            QuestionContext activeContext,
            String sharedContextText) {
        if (activeContext != null) {
            String currentPassage = activeContext.getPassage() != null ? activeContext.getPassage().trim() : null;
            if (Objects.equals(sharedContextText, currentPassage)) {
                return activeContext;
            }
        }
        QuestionContext newContext = new QuestionContext();
        newContext.setSubject(ownerSubject);
        newContext.setPassage(sharedContextText);
        return questionContextRepository.save(newContext);
    }

    private static String extractSharedContext(Map<String, Object> content) {
        if (content == null) {
            return "";
        }
        Object raw = content.get("shared_context");
        return raw != null ? String.valueOf(raw).trim() : "";
    }

    private static Boolean extractFollowUpFlag(Map<String, Object> content) {
        if (content == null) {
            return null;
        }
        Object raw = content.get("is_follow_up");
        if (raw instanceof Boolean boolValue) {
            return boolValue;
        }
        if (raw instanceof String strValue) {
            String trimmed = strValue.trim();
            if ("true".equalsIgnoreCase(trimmed)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(trimmed)) {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    private static boolean isGapFillContinuation(Map<String, Object> content) {
        if (content == null) {
            return false;
        }
        Object rawText = content.get("text");
        if (rawText == null) {
            rawText = content.get("prompt");
        }
        if (rawText == null) {
            return false;
        }
        String text = String.valueOf(rawText).trim();
        return GAP_FILL_CONTINUATION_PATTERN.matcher(text).matches();
    }

    private void failManagedJob(IngestionJob job, String errorMessage) {
        job.setStatus(IngestionJobStatus.FAILED);
        job.setErrorMessage(errorMessage);
        job.setUpdatedAt(LocalDateTime.now());
        jobRepository.save(job);
    }

    private void markJobFailed(UUID jobId, String errorMessage) {
        try {
            runInTransaction(() -> jobRepository.findById(jobId).ifPresent(job -> failManagedJob(job, errorMessage)));
        } catch (Exception persistenceEx) {
            log.error("Failed to persist FAILED status for job {}: {}",
                    jobId, errorSanitizer.sanitizeException(persistenceEx));
        }
    }

    private <T> T executeInTransaction(Supplier<T> action) {
        if (transactionTemplate != null) {
            return transactionTemplate.execute(status -> action.get());
        }
        return action.get();
    }

    private void runInTransaction(Runnable action) {
        if (transactionTemplate != null) {
            transactionTemplate.executeWithoutResult(status -> action.run());
        } else {
            action.run();
        }
    }

    private static String sanitizePdfFilename(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return DEFAULT_PDF_FILENAME;
        }
        String normalized = originalFilename.replace('\\', '/');
        int lastSlash = normalized.lastIndexOf('/');
        String baseName = (lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized).trim();
        if (baseName.isEmpty() || !baseName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            return DEFAULT_PDF_FILENAME;
        }
        return baseName;
    }

    private record ContextState(UUID contextId, String passage) {
        private static final ContextState EMPTY = new ContextState(null, null);
    }

    private record ChunkOutcome(boolean failed, JsonNode questions) {
        private static final ChunkOutcome FAILED = new ChunkOutcome(true, null);

        private static ChunkOutcome success(JsonNode questions) {
            return new ChunkOutcome(false, questions);
        }
    }

    private static final class NamedByteArrayResource extends ByteArrayResource {
        private final String filename;

        private NamedByteArrayResource(byte[] byteArray, String filename) {
            super(byteArray);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }
}
