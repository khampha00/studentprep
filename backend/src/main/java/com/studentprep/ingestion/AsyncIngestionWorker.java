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

import java.util.concurrent.CompletableFuture;

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

            processChunkAsync(job, subject, chunks, 0, null, 0, 0);

        } catch (Exception e) {
            e.printStackTrace();
            job.setStatus(IngestionJobStatus.FAILED);
            job.setErrorMessage(e.getMessage());
            jobRepository.save(job);
        }
    }

    private void processChunkAsync(IngestionJob job, Subject subject, List<String> chunks, int index, 
                                   QuestionContext currentContext, int retryCount, int rateLimitCount) {
        if (index >= chunks.size()) {
            job.setStatus(IngestionJobStatus.COMPLETED);
            jobRepository.save(job);
            return;
        }

        CompletableFuture.runAsync(() -> {
            String chunk = chunks.get(index);
            QuestionContext originalContext = currentContext;
            
            try {
                String warning = (originalContext != null) ? "Note: The previous chunk ended inside a shared context group for this specific passage:\n\n\"" + originalContext.getPassage() + "\"\n\nIf the first questions in this chunk belong to that passage, DO NOT extract the passage again, just set `is_follow_up: true` for those questions." : null;
                JsonNode structuredQuestions = llmStructuringService.structureChunk(chunk, warning);
                
                QuestionContext nextContext = originalContext;
                if (structuredQuestions.isArray()) {
                    for (JsonNode qNode : structuredQuestions) {
                        boolean isFollowUp = qNode.path("is_follow_up").asBoolean(false);
                        if (!isFollowUp) {
                            nextContext = null;
                        }

                        if (qNode.hasNonNull("shared_context") && !qNode.path("shared_context").asText().isEmpty()) {
                            nextContext = new QuestionContext();
                            nextContext.setSubject(subject);
                            nextContext.setPassage(qNode.path("shared_context").asText());
                            nextContext = questionContextRepository.save(nextContext);
                        }
                        
                        Question q = new Question();
                        q.setStatus(com.studentprep.questionbank.QuestionStatus.DRAFT);
                        q.setSubject(subject);
                        if (nextContext != null) {
                            q.setContext(nextContext);
                        }
                        Map<String, Object> contentMap = objectMapper.convertValue(qNode, new TypeReference<Map<String, Object>>() {});
                        q.setContent(contentMap);
                        questionRepository.save(q);
                    }
                }
                
                job.setProcessedChunks(index + 1);
                jobRepository.save(job);
                
                processChunkAsync(job, subject, chunks, index + 1, nextContext, 0, 0);

            } catch (Exception e) {
                String errorMsg = e.getMessage();
                if (errorMsg != null && errorMsg.contains("429")) {
                    if (rateLimitCount + 1 >= 10) {
                        job.setStatus(IngestionJobStatus.FAILED);
                        job.setErrorMessage("Gemini API rate limit exceeded after 10 waits. Please try again later.");
                        jobRepository.save(job);
                        return;
                    }
                    System.err.println("Hit 429 rate limit, patiently waiting 25s... (wait " + (rateLimitCount + 1) + "/10)");
                    java.util.concurrent.CompletableFuture.runAsync(() -> processChunkAsync(job, subject, chunks, index, originalContext, retryCount, rateLimitCount + 1), java.util.concurrent.CompletableFuture.delayedExecutor(25, java.util.concurrent.TimeUnit.SECONDS));
                } else {
                    if (retryCount + 1 >= 3) {
                        job.setStatus(IngestionJobStatus.FAILED);
                        job.setErrorMessage("LLM Extraction failed after 3 retries: " + errorMsg);
                        jobRepository.save(job);
                        return;
                    }
                    System.err.println("Failed to process chunk (attempt " + (retryCount + 1) + "/3): " + errorMsg);
                    java.util.concurrent.CompletableFuture.runAsync(() -> processChunkAsync(job, subject, chunks, index, originalContext, retryCount + 1, rateLimitCount), java.util.concurrent.CompletableFuture.delayedExecutor(3, java.util.concurrent.TimeUnit.SECONDS));
                }
            }
        });
    }
}
