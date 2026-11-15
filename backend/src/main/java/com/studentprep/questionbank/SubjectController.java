package com.studentprep.questionbank;

import com.studentprep.common.ApiResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class SubjectController {

    private static final Logger log = LoggerFactory.getLogger(SubjectController.class);
    private static final String CACHE_KEY = "exam:questions:all";

    private final SubjectRepository subjectRepository;
    private final QuestionRepository questionRepository;
    private final RedisTemplate<String, Object> redisTemplate;

    public SubjectController(
            SubjectRepository subjectRepository,
            QuestionRepository questionRepository,
            RedisTemplate<String, Object> redisTemplate) {
        this.subjectRepository = subjectRepository;
        this.questionRepository = questionRepository;
        this.redisTemplate = redisTemplate;
    }

    @GetMapping("/subjects")
    public ResponseEntity<ApiResponse<List<Subject>>> getAllSubjects() {
        return ResponseEntity.ok(ApiResponse.of(subjectRepository.findAll()));
    }

    @GetMapping("/admin/subjects")
    public ResponseEntity<ApiResponse<List<Subject>>> getAllSubjectsAdmin() {
        return ResponseEntity.ok(ApiResponse.of(subjectRepository.findAll()));
    }

    @PostMapping("/admin/subjects")
    public ResponseEntity<ApiResponse<Subject>> createSubject(@Valid @RequestBody Subject subject) {
        if (subject.getName() == null || subject.getName().trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        Subject savedSubject = subjectRepository.save(subject);
        return ResponseEntity.ok(ApiResponse.of(savedSubject));
    }

    @GetMapping("/admin/subjects/{id}")
    public ResponseEntity<ApiResponse<Subject>> getSubjectById(@PathVariable UUID id) {
        return subjectRepository.findById(id)
                .map(s -> ResponseEntity.ok(ApiResponse.of(s)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/admin/subjects/{id}")
    public ResponseEntity<ApiResponse<Subject>> updateSubject(
            @PathVariable UUID id,
            @Valid @RequestBody Subject updatedSubject) {
        if (updatedSubject.getName() == null || updatedSubject.getName().trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        return subjectRepository.findById(id)
                .map(subject -> {
                    subject.setName(updatedSubject.getName());
                    Subject saved = subjectRepository.save(subject);
                    evictQuestionsCache();
                    return ResponseEntity.ok(ApiResponse.of(saved));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/admin/subjects/{id}")
    public ResponseEntity<ApiResponse<Map<String, String>>> deleteSubject(@PathVariable UUID id) {
        if (!subjectRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }

        if (questionRepository.existsBySubject_Id(id)) {
            return ResponseEntity.badRequest().build();
        }

        subjectRepository.deleteById(id);
        evictQuestionsCache();
        return ResponseEntity.ok(ApiResponse.of(Map.of("status", "DELETED")));
    }

    private void evictQuestionsCache() {
        Runnable evictTask = () -> {
            try {
                redisTemplate.delete(CACHE_KEY);
                log.info("Evicted questions cache {} on subject change", CACHE_KEY);
            } catch (DataAccessException ex) {
                log.warn("Failed to evict questions cache {} on subject change: {}. Continuing fail-open.", CACHE_KEY, ex.getMessage());
            } catch (Exception ex) {
                log.warn("Unexpected error evicting questions cache: {}", ex.getMessage());
            }
        };

        if (TransactionSynchronizationManager.isActualTransactionActive()) {
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
}
