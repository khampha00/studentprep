package com.studentprep.questionbank;

import com.studentprep.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class SubjectController {

    private final SubjectRepository subjectRepository;
    private final QuestionRepository questionRepository;
    private final QuestionContextRepository contextRepository;
    private final RedisTemplate<String, Object> redisTemplate;

    public SubjectController(
            SubjectRepository subjectRepository,
            QuestionRepository questionRepository,
            QuestionContextRepository contextRepository,
            RedisTemplate<String, Object> redisTemplate) {
        this.subjectRepository = subjectRepository;
        this.questionRepository = questionRepository;
        this.contextRepository = contextRepository;
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
    @Transactional
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
                    QuestionService.evictQuestionsCache(redisTemplate);
                    return ResponseEntity.ok(ApiResponse.of(saved));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Deletes a subject only when no active or draft questions remain, cleaning orphaned
     * question contexts and scheduling Redis cache eviction after commit.
     */
    @DeleteMapping("/admin/subjects/{id}")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, String>>> deleteSubject(@PathVariable UUID id) {
        if (!subjectRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Subject not found");
        }

        if (questionRepository.existsBySubject_Id(id)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Cannot delete subject while questions remain. Delete active and draft questions first.");
        }

        contextRepository.deleteBySubject_Id(id);
        contextRepository.flush();
        subjectRepository.deleteById(id);
        QuestionService.evictQuestionsCache(redisTemplate);
        return ResponseEntity.ok(ApiResponse.of(Map.of("status", "DELETED")));
    }
}
