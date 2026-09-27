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
            // Post-process: retroactively link questions that have shared_context in their JSONB
            // but no context_id set (because the LLM failed to mark is_follow_up properly)
            postProcessContextGroups(subject);
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
                    // First pass: collect all questions and identify context groups
                    List<JsonNode> questionNodes = new ArrayList<>();
                    for (JsonNode qNode : structuredQuestions) {
                        questionNodes.add(qNode);
                    }
                    
                    for (int qi = 0; qi < questionNodes.size(); qi++) {
                        JsonNode qNode = questionNodes.get(qi);
                        boolean isFollowUp = qNode.path("is_follow_up").asBoolean(false);
                        boolean hasSharedContext = qNode.hasNonNull("shared_context") && !qNode.path("shared_context").asText().isEmpty();
                        
                        if (hasSharedContext) {
                            // This question introduces a new shared context
                            nextContext = new QuestionContext();
                            nextContext.setSubject(subject);
                            nextContext.setPassage(qNode.path("shared_context").asText());
                            nextContext = questionContextRepository.save(nextContext);
                        } else if (!isFollowUp && nextContext != null) {
                            // Not a follow-up and no shared_context — check if the NEXT question
                            // has is_follow_up or shared_context pointing to a different group.
                            // If this question is truly standalone, break the context chain.
                            nextContext = null;
                        }
                        // If isFollowUp is true but no shared_context, keep using nextContext (correct behavior)

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
        
        for (int i = 0; i < questions.size(); i++) {
            Question q = questions.get(i);
            
            // Skip questions that already have a context linked
            if (q.getContext() != null) {
                activeContext = q.getContext();
                continue;
            }
            
            Map<String, Object> content = q.getContent();
            if (content == null) continue;
            
            String sharedContextText = content.get("shared_context") != null 
                    ? String.valueOf(content.get("shared_context")).trim() : "";
            boolean isFollowUp = Boolean.TRUE.equals(content.get("is_follow_up"));
            
            if (!sharedContextText.isEmpty()) {
                // This question introduces a new shared context
                activeContext = new QuestionContext();
                activeContext.setSubject(subject);
                activeContext.setPassage(sharedContextText);
                activeContext = questionContextRepository.save(activeContext);
                
                q.setContext(activeContext);
                questionRepository.save(q);
                
                System.out.println("[PostProcess] Created context " + activeContext.getId() 
                        + " for question " + q.getId() + " (shared_context found)");
            } else if (isFollowUp && activeContext != null) {
                // Follow-up question that should share the previous context
                q.setContext(activeContext);
                questionRepository.save(q);
                
                System.out.println("[PostProcess] Linked question " + q.getId() 
                        + " to context " + activeContext.getId() + " (is_follow_up=true)");
            } else {
                // Standalone question — break the context chain
                activeContext = null;
            }
        }
    }
}
