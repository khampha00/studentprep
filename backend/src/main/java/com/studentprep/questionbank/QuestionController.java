package com.studentprep.questionbank;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/questions")
public class QuestionController {

    private final QuestionRepository repository;

    public QuestionController(QuestionRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public ResponseEntity<List<Question>> getQuestionsByStatus(
            @RequestParam(defaultValue = "DRAFT") String status,
            @RequestParam(required = false) UUID subjectId) {
        if (subjectId != null) {
            return ResponseEntity.ok(repository.findByStatusAndSubjectIdOrderByCreatedAtAsc(status, subjectId));
        }
        return ResponseEntity.ok(repository.findByStatusOrderByCreatedAtAsc(status));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Question> updateQuestionStatus(@PathVariable UUID id, @RequestBody Question updateRequest) {
        return repository.findById(id).map(q -> {
            q.setStatus(updateRequest.getStatus());
            q.setContent(updateRequest.getContent());
            return ResponseEntity.ok(repository.save(q));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteQuestion(@PathVariable UUID id) {
        repository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/drafts/bulk")
    public ResponseEntity<Void> deleteDraftsBulk(@RequestParam UUID subjectId) {
        repository.deleteByStatusAndSubjectId("DRAFT", subjectId);
        return ResponseEntity.noContent().build();
    }
}
