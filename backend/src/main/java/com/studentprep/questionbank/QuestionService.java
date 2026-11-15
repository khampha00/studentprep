package com.studentprep.questionbank;

import com.studentprep.questionbank.dto.ContextLinkRequest;
import com.studentprep.questionbank.dto.ContextUpdateRequest;
import com.studentprep.questionbank.dto.QuestionUpdateRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class QuestionService implements QuestionInternalAPI {

    private static final Logger log = LoggerFactory.getLogger(QuestionService.class);
    private static final String CACHE_KEY = "exam:questions:all";
    private static final Pattern GAP_FILL_PROMPT_PATTERN =
            Pattern.compile("^\\s*fill\\s+in\\s+(?:the\\s+)?gap\\b.*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final QuestionRepository repository;
    private final QuestionContextRepository contextRepository;
    private final RedisTemplate<String, Object> redisTemplate;

    public QuestionService(QuestionRepository repository, QuestionContextRepository contextRepository, RedisTemplate<String, Object> redisTemplate) {
        this.repository = repository;
        this.contextRepository = contextRepository;
        this.redisTemplate = redisTemplate;
    }

    static void evictQuestionsCache(RedisTemplate<String, Object> redisTemplate) {
        Runnable evictTask = () -> {
            if (redisTemplate == null) {
                log.warn("RedisTemplate is not configured; skipping cache eviction for {}", CACHE_KEY);
                return;
            }
            try {
                redisTemplate.delete(CACHE_KEY);
                log.info("Evicted questions cache {} from Redis", CACHE_KEY);
            } catch (DataAccessException ex) {
                log.warn("Failed to evict questions cache {} from Redis: {}. Continuing fail-open.", CACHE_KEY, ex.getMessage());
            } catch (Exception ex) {
                log.warn("Unexpected error evicting questions cache: {}", ex.getMessage());
            }
        };

        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evictTask.run();
                }
            });
        } else {
            evictTask.run();
        }
    }

    private void evictQuestionsCache() {
        evictQuestionsCache(this.redisTemplate);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Question> getActiveQuestions() {
        return repository.findByStatusOrderByCreatedAtAsc(QuestionStatus.ACTIVE);
    }

    @Transactional(readOnly = true)
    public List<Question> getQuestions(String status, UUID subjectId) {
        QuestionStatus qStatus;
        try {
            qStatus = QuestionStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            return new ArrayList<>();
        }
        
        if (subjectId != null) {
            return repository.findByStatusAndSubject_IdOrderByCreatedAtAsc(qStatus, subjectId);
        } else {
            return repository.findByStatusOrderByCreatedAtAsc(qStatus);
        }
    }

    /**
     * Validates and applies typed question updates, enforcing active-question completeness
     * and scheduling Redis question-cache eviction after transaction commit.
     */
    @Transactional
    public Question updateQuestionStatus(UUID id, QuestionUpdateRequest updateRequest) {
        if (updateRequest == null || updateRequest.status() == null || updateRequest.content() == null) {
            throw new IllegalArgumentException("Question status and content are required");
        }

        Question question = repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Question not found"));

        Map<String, Object> content = updateRequest.content();
        if (updateRequest.status() == QuestionStatus.ACTIVE) {
            validateActiveContent(content);
        }

        question.setStatus(updateRequest.status());
        if (updateRequest.topic() != null) {
            String trimmedTopic = updateRequest.topic().trim();
            question.setTopic(trimmedTopic.isEmpty() ? null : trimmedTopic);
        }
        question.setContent(new LinkedHashMap<>(content));

        Question saved = repository.save(question);
        evictQuestionsCache();
        return saved;
    }

    @Transactional
    public Question updateQuestionStatus(UUID id, Question updateRequest) {
        return repository.findById(id).map(q -> {
            q.setStatus(updateRequest.getStatus());
            q.setContent(updateRequest.getContent());
            Question saved = repository.save(q);
            evictQuestionsCache();
            return saved;
        }).orElseThrow(() -> new IllegalArgumentException("Question not found"));
    }

    private void validateActiveContent(Map<String, Object> content) {
        Object textObj = content.get("text");
        if (textObj == null || String.valueOf(textObj).trim().isEmpty()) {
            throw new IllegalArgumentException("Active questions must include non-blank question text");
        }

        Object correctOptObj = content.get("correctOption");
        String correctOption = correctOptObj != null ? String.valueOf(correctOptObj).trim() : "";
        if (correctOption.isEmpty()) {
            throw new IllegalArgumentException("Active questions must specify a valid correctOption");
        }

        Object optionsObj = content.get("options");
        if (optionsObj != null) {
            if (!(optionsObj instanceof Map<?, ?> optionsMap)) {
                throw new IllegalArgumentException("Question options must be a JSON object");
            }
            if (!optionsMap.isEmpty()) {
                boolean matchedOption = optionsMap.keySet().stream()
                        .anyMatch(key -> key != null && String.valueOf(key).trim().equalsIgnoreCase(correctOption));
                if (!matchedOption) {
                    throw new IllegalArgumentException(
                            "Active question correctOption '" + correctOption + "' must match one of the available options");
                }
            }
        }
    }

    @Transactional
    public void deleteQuestion(UUID id) {
        Question q = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Question not found"));
        QuestionContext ctx = q.getContext();
        repository.delete(q);
        if (ctx != null && repository.countByContext_Id(ctx.getId()) == 0) {
            contextRepository.delete(ctx);
        }
        evictQuestionsCache();
    }

    @Transactional
    public void deleteDraftsBulk(UUID subjectId) {
        repository.deleteByStatusAndSubject_Id(QuestionStatus.DRAFT, subjectId);
        contextRepository.deleteOrphanedContexts();
    }

    @Transactional
    public void deleteAllActive(UUID subjectId) {
        repository.deleteByStatusAndSubject_Id(QuestionStatus.ACTIVE, subjectId);
        contextRepository.deleteOrphanedContexts();
        evictQuestionsCache();
    }

    @Transactional
    public void approveAllDrafts(UUID subjectId) {
        List<Question> drafts = repository.findByStatusAndSubject_IdOrderByCreatedAtAsc(QuestionStatus.DRAFT, subjectId);
        List<String> missingAnswers = new ArrayList<>();
        
        for (Question q : drafts) {
            if (!canBeApproved(q)) {
                Object qNum = q.getContent() != null ? q.getContent().get("questionNumber") : null;
                missingAnswers.add(qNum != null ? String.valueOf(qNum) : "Unknown");
            }
        }
        
        if (!missingAnswers.isEmpty()) {
            throw new IllegalStateException("Cannot approve. The following questions are missing correct answers: " + String.join(", ", missingAnswers));
        }
        
        for (Question q : drafts) {
            q.setStatus(QuestionStatus.ACTIVE);
            repository.save(q);
        }
        evictQuestionsCache();
    }

    @Transactional
    public void ungroupQuestion(UUID id) {
        Question q = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Question not found"));
        QuestionContext ctx = q.getContext();
        if (ctx != null) {
            q.setContext(null);
            repository.save(q);
            if (repository.countByContext_Id(ctx.getId()) == 0) {
                contextRepository.delete(ctx);
            }
            evictQuestionsCache();
        }
    }

    @Transactional
    public void ungroupContext(UUID contextId) {
        List<Question> questions = repository.findByContext_Id(contextId);
        boolean anyActive = false;
        
        for (Question q : questions) {
            if (QuestionStatus.DRAFT.equals(q.getStatus())) {
                q.setContext(null);
                repository.save(q);
            } else {
                anyActive = true;
            }
        }
        
        if (!anyActive) {
            contextRepository.deleteById(contextId);
        }
        evictQuestionsCache();
    }

    /**
     * Updates a shared {@link QuestionContext}'s passage text and attached media assets,
     * rejecting updates that leave both passage and assets empty, and evicts the active question cache.
     */
    @Transactional
    public QuestionContext updateContext(UUID contextId, ContextUpdateRequest updateRequest) {
        if (updateRequest == null) {
            throw new IllegalArgumentException("Context update payload is required");
        }
        QuestionContext context = contextRepository.findById(contextId)
                .orElseThrow(() -> new NoSuchElementException("Question context not found"));

        String nextPassage = updateRequest.passage() != null
                ? updateRequest.passage()
                : (context.getPassage() != null ? context.getPassage() : "");
        List<Map<String, Object>> nextAssets = updateRequest.assets() != null
                ? copyAssets(updateRequest.assets())
                : copyAssets(context.getAssets());

        if (nextPassage.isBlank() && nextAssets.isEmpty()) {
            throw new IllegalArgumentException("Shared context must include non-blank text or at least one image");
        }

        context.setPassage(nextPassage);
        context.setAssets(nextAssets);
        QuestionContext saved = contextRepository.save(context);
        evictQuestionsCache();
        return saved;
    }

    private static List<Map<String, Object>> copyAssets(List<Map<String, Object>> rawAssets) {
        List<Map<String, Object>> copied = new ArrayList<>();
        if (rawAssets == null) {
            return copied;
        }
        for (Map<String, Object> entry : rawAssets) {
            if (entry != null && !entry.isEmpty()) {
                copied.add(new LinkedHashMap<>(entry));
            }
        }
        return copied;
    }

    @Transactional
    public Question linkQuestion(UUID id, ContextLinkRequest req) {
        Question q = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Question not found"));
        if (req.getContextId() != null) {
            contextRepository.findById(req.getContextId()).ifPresent(q::setContext);
        } else if (req.getNewPassage() != null && !req.getNewPassage().isBlank()) {
            QuestionContext newCtx = new QuestionContext();
            newCtx.setPassage(req.getNewPassage());
            newCtx.setSubject(q.getSubject());
            QuestionContext savedCtx = contextRepository.save(newCtx);
            q.setContext(savedCtx);
        }
        Question saved = repository.save(q);
        evictQuestionsCache();
        return saved;
    }

    @Transactional
    public void approveContextGroup(UUID contextId) {
        List<Question> questions = repository.findByContext_Id(contextId);
        for (Question q : questions) {
            if (QuestionStatus.DRAFT.equals(q.getStatus()) && !canBeApproved(q)) {
                throw new IllegalStateException("Question missing correct option");
            }
        }
        for (Question q : questions) {
            if (QuestionStatus.DRAFT.equals(q.getStatus())) {
                q.setStatus(QuestionStatus.ACTIVE);
                repository.save(q);
            }
        }
        evictQuestionsCache();
    }
    
    /**
     * Re-evaluates passage context links across a subject's active and draft questions
     * using explicit {@code shared_context} and {@code is_follow_up} semantics.
     */
    @Transactional
    public void relinkContextsForSubject(UUID subjectId) {
        List<Question> questions = repository.findByStatusAndSubject_IdOrderByCreatedAtAsc(QuestionStatus.ACTIVE, subjectId);
        List<Question> drafts = repository.findByStatusAndSubject_IdOrderByCreatedAtAsc(QuestionStatus.DRAFT, subjectId);

        // Combine both active and draft, sorted by creation time
        List<Question> allQuestions = new ArrayList<>(questions.size() + drafts.size());
        allQuestions.addAll(questions);
        allQuestions.addAll(drafts);
        allQuestions.sort(Comparator.comparing(Question::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())));

        QuestionContext activeContext = null;

        for (Question q : allQuestions) {
            Map<String, Object> content = q.getContent();
            if (content == null) {
                activeContext = q.getContext();
                continue;
            }

            String sharedContextText = content.get("shared_context") != null
                    ? String.valueOf(content.get("shared_context")).trim() : "";
            Boolean explicitFollowUp = extractFollowUpFlag(content.get("is_follow_up"));

            if (q.getContext() != null) {
                if (sharedContextText.isEmpty() && Boolean.FALSE.equals(explicitFollowUp)) {
                    q.setContext(null);
                    repository.save(q);
                    activeContext = null;
                } else {
                    activeContext = q.getContext();
                }
                continue;
            }

            if (!sharedContextText.isEmpty()) {
                activeContext = new QuestionContext();
                activeContext.setSubject(q.getSubject());
                activeContext.setPassage(sharedContextText);
                activeContext = contextRepository.save(activeContext);

                q.setContext(activeContext);
                repository.save(q);
            } else if (Boolean.TRUE.equals(explicitFollowUp)) {
                if (activeContext != null) {
                    q.setContext(activeContext);
                    repository.save(q);
                }
            } else if (Boolean.FALSE.equals(explicitFollowUp)) {
                q.setContext(null);
                activeContext = null;
            } else if (activeContext != null && isGapFillContinuation(content)) {
                q.setContext(activeContext);
                repository.save(q);
            } else {
                activeContext = null;
            }
        }
        evictQuestionsCache();
    }

    private static Boolean extractFollowUpFlag(Object rawFlag) {
        if (rawFlag instanceof Boolean flag) {
            return flag;
        }
        if (rawFlag == null) {
            return null;
        }
        String text = String.valueOf(rawFlag).trim();
        if ("true".equalsIgnoreCase(text)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(text)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static boolean isGapFillContinuation(Map<String, Object> content) {
        Object textObj = content.get("text");
        if (textObj == null) {
            return false;
        }
        return GAP_FILL_PROMPT_PATTERN.matcher(String.valueOf(textObj)).matches();
    }
    
    private boolean canBeApproved(Question q) {
        Map<String, Object> content = q.getContent();
        if (content == null || !content.containsKey("correctOption")) {
            return false;
        }
        Object opt = content.get("correctOption");
        return opt != null && !String.valueOf(opt).trim().isEmpty();
    }
}
