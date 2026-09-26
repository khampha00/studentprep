package com.studentprep.questionbank;

import com.studentprep.questionbank.dto.ContextLinkRequest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;

@Service
public class QuestionService implements QuestionInternalAPI {

    private final QuestionRepository repository;
    private final QuestionContextRepository contextRepository;
    private final RedisTemplate<String, Object> redisTemplate;

    public QuestionService(QuestionRepository repository, QuestionContextRepository contextRepository, RedisTemplate<String, Object> redisTemplate) {
        this.repository = repository;
        this.contextRepository = contextRepository;
        this.redisTemplate = redisTemplate;
    }

    private void evictQuestionsCache() {
        redisTemplate.delete("exam:questions:all");
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
    
    private boolean canBeApproved(Question q) {
        Map<String, Object> content = q.getContent();
        if (content == null || !content.containsKey("correctOption")) {
            return false;
        }
        Object opt = content.get("correctOption");
        return opt != null && !String.valueOf(opt).trim().isEmpty();
    }
}
