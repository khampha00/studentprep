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
    public ResponseEntity<List<Question>> getQuestionsByStatus(@RequestParam(defaultValue = "DRAFT") String status) {
        return ResponseEntity.ok(repository.findByStatus(status));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Question> updateQuestionStatus(@PathVariable UUID id, @RequestBody Question updateRequest) {
        return repository.findById(id).map(q -> {
            q.setStatus(updateRequest.getStatus());
            q.setContent(updateRequest.getContent());
            return ResponseEntity.ok(repository.save(q));
        }).orElse(ResponseEntity.notFound().build());
    }
}
