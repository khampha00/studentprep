package com.studentprep.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.studentprep.ingestion.job.IngestionJob;
import com.studentprep.ingestion.job.IngestionJobRepository;
import com.studentprep.ingestion.job.IngestionJobStatus;
import com.studentprep.questionbank.Question;
import com.studentprep.questionbank.QuestionRepository;
import com.studentprep.questionbank.QuestionContext;
import com.studentprep.questionbank.QuestionContextRepository;
import com.studentprep.questionbank.Subject;
import com.studentprep.questionbank.SubjectRepository;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AsyncIngestionWorker {

    private final LlmStructuringService llmStructuringService;
    private final IngestionJobRepository jobRepository;
    private final QuestionRepository questionRepository;
    private final SubjectRepository subjectRepository;
    private final QuestionContextRepository questionContextRepository;
    private final ObjectMapper objectMapper;

    public AsyncIngestionWorker(LlmStructuringService llmStructuringService,
                                IngestionJobRepository jobRepository,
                                QuestionRepository questionRepository,
                                SubjectRepository subjectRepository,
                                QuestionContextRepository questionContextRepository,
                                ObjectMapper objectMapper) {
        this.llmStructuringService = llmStructuringService;
        this.jobRepository = jobRepository;
        this.questionRepository = questionRepository;
        this.subjectRepository = subjectRepository;
        this.questionContextRepository = questionContextRepository;
        this.objectMapper = objectMapper;
    }

    @Async
    public void processMarkdown(UUID jobId, UUID subjectId, String markdown) {
        IngestionJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null) return;

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

            // Find natural split points
            List<Integer> splitPoints = new ArrayList<>();
            splitPoints.add(0);
            Matcher spMatcher = Pattern.compile("\\n(Question\\s+|Q)?\\d+[\\.\\)]\\s").matcher(markdown);
            while (spMatcher.find()) {
                splitPoints.add(spMatcher.start());
            }
            splitPoints.add(markdown.length());

            int chunkSize = 4000;
            int currentStart = 0;

            // First count total chunks to update the job
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

                chunks.add(markdown.substring(currentStart, bestSplit));
                currentStart = bestSplit;
            }

            job.setTotalChunks(chunks.size());
            jobRepository.save(job);

            int processedCount = 0;
            QuestionContext currentContext = null;

            for (String chunk : chunks) {
                boolean success = false;
                int maxRetries = 3;
                int maxRateLimitWaits = 10;
                int retryCount = 0;
                int rateLimitCount = 0;
                QuestionContext originalContext = currentContext;
                
                while (!success) {
                    currentContext = originalContext;
                    try {
                        String warning = (currentContext != null) ? "Note: The previous chunk ended inside a shared context group for this specific passage:\n\n\"" + currentContext.getPassage() + "\"\n\nIf the first questions in this chunk belong to that passage, DO NOT extract the passage again, just set `is_follow_up: true` for those questions." : null;
                        JsonNode structuredQuestions = llmStructuringService.structureChunk(chunk, warning);
                        
                        if (structuredQuestions.isArray()) {
                            for (JsonNode qNode : structuredQuestions) {
                                boolean isFollowUp = qNode.path("is_follow_up").asBoolean(false);
                                if (!isFollowUp) {
                                    currentContext = null;
                                }

                                if (qNode.hasNonNull("shared_context") && !qNode.path("shared_context").asText().isEmpty()) {
                                    currentContext = new QuestionContext();
                                    currentContext.setSubject(subject);
                                    currentContext.setPassage(qNode.path("shared_context").asText());
                                    currentContext = questionContextRepository.save(currentContext);
                                }
                                
                                Question q = new Question();
                                q.setStatus("DRAFT");
                                q.setSubject(subject);
                                if (currentContext != null) {
                                    q.setContext(currentContext);
                                }
                                Map<String, Object> contentMap = objectMapper.convertValue(qNode, new TypeReference<Map<String, Object>>() {});
                                q.setContent(contentMap);
                                questionRepository.save(q);
                            }
                        }
                        success = true;
                    } catch (Exception e) {
                        String errorMsg = e.getMessage();
                        if (errorMsg != null && errorMsg.contains("429")) {
                            rateLimitCount++;
                            if (rateLimitCount >= maxRateLimitWaits) {
                                throw new RuntimeException("Gemini API rate limit exceeded after " + maxRateLimitWaits + " waits. Please try again later.");
                            }
                            System.err.println("Hit 429 rate limit, patiently waiting 25s... (wait " + rateLimitCount + "/" + maxRateLimitWaits + ")");
                            try { Thread.sleep(25000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                            // Do NOT increment retryCount — rate limits are not real failures
                        } else {
                            retryCount++;
                            System.err.println("Failed to process chunk (attempt " + retryCount + "/" + maxRetries + "): " + errorMsg);
                            if (retryCount >= maxRetries) {
                                throw new RuntimeException("LLM Extraction failed after " + maxRetries + " retries: " + errorMsg);
                            }
                            try { Thread.sleep(3000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                        }
                    }
                }

                processedCount++;
                job.setProcessedChunks(processedCount);
                jobRepository.save(job);
            }

            job.setStatus(IngestionJobStatus.COMPLETED);
            jobRepository.save(job);

        } catch (Exception e) {
            e.printStackTrace();
            job.setStatus(IngestionJobStatus.FAILED);
            job.setErrorMessage(e.getMessage());
            jobRepository.save(job);
        }
    }
}
