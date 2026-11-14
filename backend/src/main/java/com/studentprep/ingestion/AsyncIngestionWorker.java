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
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Processes extracted exam markdown asynchronously in chunks and persists sanitized failure states.
 */
@Service
public class AsyncIngestionWorker {

    private static final Logger log = LoggerFactory.getLogger(AsyncIngestionWorker.class);
    private static final long DEFAULT_RETRY_DELAY_MS = 3000L;
    private static final long RATE_LIMIT_DELAY_SECONDS = 25L;
    private static final int MAX_RETRIES = 3;
    private static final int MAX_RATE_LIMIT_WAITS = 10;

    private final LlmStructuringService llmStructuringService;
    private final IngestionJobRepository jobRepository;
    private final QuestionRepository questionRepository;
    private final SubjectRepository subjectRepository;
    private final QuestionContextRepository questionContextRepository;
    private final ObjectMapper objectMapper;
    private final ErrorSanitizer errorSanitizer;
    private final long retryDelayMs;

    @Autowired
    public AsyncIngestionWorker(
            LlmStructuringService llmStructuringService,
            IngestionJobRepository jobRepository,
            QuestionRepository questionRepository,
            SubjectRepository subjectRepository,
            QuestionContextRepository questionContextRepository,
            ObjectMapper objectMapper,
            ErrorSanitizer errorSanitizer,
            @Value("${ingestion.retry-delay-ms:3000}") Long retryDelayMs) {
        this.llmStructuringService = llmStructuringService;
        this.jobRepository = jobRepository;
        this.questionRepository = questionRepository;
        this.subjectRepository = subjectRepository;
        this.questionContextRepository = questionContextRepository;
        this.objectMapper = objectMapper;
        this.errorSanitizer = errorSanitizer != null ? errorSanitizer : new ErrorSanitizer("", "", "");
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
                llmStructuringService,
                jobRepository,
                questionRepository,
                subjectRepository,
                questionContextRepository,
                objectMapper,
                new ErrorSanitizer("", "", ""),
                DEFAULT_RETRY_DELAY_MS
        );
    }

    @Async
    public void processMarkdown(UUID jobId, UUID subjectId, String markdown) {
        IngestionJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            return;
        }

        Subject subject = subjectRepository.findById(subjectId).orElse(null);
        if (subject == null) {
            job.setStatus(IngestionJobStatus.FAILED);
            job.setErrorMessage("Subject not found");
            jobRepository.save(job);
            return;
        }

        try {
            job.setStatus(IngestionJobStatus.PROCESSING);
            jobRepository.save(job);

            List<Integer> splitPoints = new ArrayList<>();
            splitPoints.add(0);
            Matcher spMatcher = Pattern.compile("\\n(Question\\s+|Q)?\\d+[\\.\\)]\\s").matcher(markdown);
            while (spMatcher.find()) {
                splitPoints.add(spMatcher.start());
            }
            splitPoints.add(markdown.length());

            int chunkSize = 4000;
            int currentStart = 0;

            List<String> chunks = new ArrayList<>();
            while (currentStart < markdown.length()) {
                int targetEnd = Math.min(currentStart + chunkSize, markdown.length());
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

                chunks.add(markdown.substring(currentStart, bestSplit));
                currentStart = bestSplit;
            }

            job.setTotalChunks(chunks.size());
            jobRepository.save(job);

            processChunkAsync(job, subject, chunks, 0, null, 0, 0);

        } catch (Exception e) {
            String sanitizedError = errorSanitizer.sanitizeException(e);
            log.error("Failed to initialize markdown ingestion for job {}: {}", jobId, sanitizedError);
            job.setStatus(IngestionJobStatus.FAILED);
            job.setErrorMessage(sanitizedError);
            jobRepository.save(job);
        }
    }

    private void processChunkAsync(
            IngestionJob job,
            Subject subject,
            List<String> chunks,
            int index,
            QuestionContext currentContext,
            int retryCount,
            int rateLimitCount) {
        if (index >= chunks.size()) {
            postProcessContextGroups(subject);
            job.setStatus(IngestionJobStatus.COMPLETED);
            jobRepository.save(job);
            return;
        }

        CompletableFuture.runAsync(() -> {
            String chunk = chunks.get(index);
            QuestionContext originalContext = currentContext;

            try {
                String warning = (originalContext != null)
                        ? "Note: The previous chunk ended inside a shared context group for this specific passage:\n\n\""
                                + originalContext.getPassage()
                                + "\"\n\nIf the first questions in this chunk belong to that passage, DO NOT extract the passage again, just set `is_follow_up: true` for those questions."
                        : null;
                JsonNode structuredQuestions = llmStructuringService.structureChunk(chunk, warning);

                QuestionContext nextContext = originalContext;
                if (structuredQuestions != null && structuredQuestions.isArray()) {
                    List<JsonNode> questionNodes = new ArrayList<>();
                    for (JsonNode qNode : structuredQuestions) {
                        questionNodes.add(qNode);
                    }

                    for (JsonNode qNode : questionNodes) {
                        boolean hasSharedContext = qNode.hasNonNull("shared_context")
                                && !qNode.path("shared_context").asText().isEmpty();

                        if (hasSharedContext) {
                            nextContext = new QuestionContext();
                            nextContext.setSubject(subject);
                            nextContext.setPassage(qNode.path("shared_context").asText());
                            nextContext = questionContextRepository.save(nextContext);
                        }

                        Question q = new Question();
                        q.setStatus(QuestionStatus.DRAFT);
                        q.setSubject(subject);
                        if (nextContext != null) {
                            q.setContext(nextContext);
                        }
                        Map<String, Object> contentMap = objectMapper.convertValue(
                                qNode,
                                new TypeReference<Map<String, Object>>() {}
                        );
                        q.setContent(contentMap);
                        questionRepository.save(q);
                    }
                }

                job.setProcessedChunks(index + 1);
                jobRepository.save(job);

                processChunkAsync(job, subject, chunks, index + 1, nextContext, 0, 0);

            } catch (Exception e) {
                String sanitizedError = errorSanitizer.sanitizeException(e);
                if (isRateLimitFailure(e, sanitizedError)) {
                    if (rateLimitCount + 1 >= MAX_RATE_LIMIT_WAITS) {
                        log.warn("Gemini API rate limit exceeded for job {} after {} waits", job.getId(), MAX_RATE_LIMIT_WAITS);
                        job.setStatus(IngestionJobStatus.FAILED);
                        job.setErrorMessage("Gemini API rate limit exceeded after 10 waits. Please try again later.");
                        jobRepository.save(job);
                        return;
                    }
                    log.warn("Hit 429 rate limit on job {}, waiting {}s (wait {}/{})",
                            job.getId(), RATE_LIMIT_DELAY_SECONDS, rateLimitCount + 1, MAX_RATE_LIMIT_WAITS);
                    CompletableFuture.runAsync(
                            () -> processChunkAsync(job, subject, chunks, index, originalContext, retryCount, rateLimitCount + 1),
                            CompletableFuture.delayedExecutor(RATE_LIMIT_DELAY_SECONDS, TimeUnit.SECONDS)
                    );
                } else {
                    if (retryCount + 1 >= MAX_RETRIES) {
                        log.error("LLM extraction failed for job {} after {} retries: {}",
                                job.getId(), MAX_RETRIES, sanitizedError);
                        job.setStatus(IngestionJobStatus.FAILED);
                        job.setErrorMessage(errorSanitizer.sanitizeMessage(
                                "LLM Extraction failed after 3 retries: " + sanitizedError
                        ));
                        jobRepository.save(job);
                        return;
                    }
                    log.warn("Failed to process chunk {} for job {} (attempt {}/{}): {}",
                            index, job.getId(), retryCount + 1, MAX_RETRIES, sanitizedError);
                    CompletableFuture.runAsync(
                            () -> processChunkAsync(job, subject, chunks, index, originalContext, retryCount + 1, rateLimitCount),
                            CompletableFuture.delayedExecutor(retryDelayMs, TimeUnit.MILLISECONDS)
                    );
                }
            }
        });
    }

    private static boolean isRateLimitFailure(Exception ex, String sanitizedError) {
        if (ex instanceof RestClientResponseException responseException
                && responseException.getStatusCode().value() == 429) {
            return true;
        }
        return sanitizedError != null && sanitizedError.contains("429");
    }

    /**
     * Post-processing step: retroactively creates QuestionContext records for questions
     * that have shared_context text in their JSONB content but no context_id set.
     * This handles the case where the LLM fails to mark follow-up questions with is_follow_up: true.
     *
     * Logic: Iterate questions in creation order. When a question has shared_context text,
     * create a QuestionContext and link it. Then link all subsequent questions that have
     * is_follow_up: true OR have no shared_context of their own (until we hit another
     * question with a different shared_context or a clear standalone question).
     */
    @Transactional
    public void postProcessContextGroups(Subject subject) {
        List<Question> questions = questionRepository.findBySubjectOrderByCreatedAtAsc(subject);

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

            String sharedContextText = content.get("shared_context") != null
                    ? String.valueOf(content.get("shared_context")).trim()
                    : "";

            if (!sharedContextText.isEmpty()) {
                activeContext = new QuestionContext();
                activeContext.setSubject(subject);
                activeContext.setPassage(sharedContextText);
                activeContext = questionContextRepository.save(activeContext);

                q.setContext(activeContext);
                questionRepository.save(q);

                log.debug("[PostProcess] Created context {} for question {} (shared_context found)",
                        activeContext.getId(), q.getId());
            } else if (activeContext != null) {
                q.setContext(activeContext);
                questionRepository.save(q);

                log.debug("[PostProcess] Linked question {} to context {} (assumed follow-up)",
                        q.getId(), activeContext.getId());
            } else {
                activeContext = null;
            }
        }
    }
}
