package com.studentprep.questionbank;

import com.studentprep.questionbank.dto.ContextLinkRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/questions")
public class QuestionController {

    private final QuestionService questionService;

    public QuestionController(QuestionService questionService) {
        this.questionService = questionService;
    }

    @GetMapping
    public ResponseEntity<List<Question>> getQuestionsByStatus(
            @RequestParam(defaultValue = "DRAFT") String status,
            @RequestParam(required = false) UUID subjectId) {
        return ResponseEntity.ok(questionService.getQuestions(status, subjectId));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Question> updateQuestionStatus(@PathVariable UUID id, @RequestBody Question updateRequest) {
        try {
            return ResponseEntity.ok(questionService.updateQuestionStatus(id, updateRequest));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteQuestion(@PathVariable UUID id) {
        try {
            questionService.deleteQuestion(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/drafts/bulk")
    public ResponseEntity<Void> deleteDraftsBulk(@RequestParam UUID subjectId) {
        questionService.deleteDraftsBulk(subjectId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/active/bulk")
    public ResponseEntity<Void> deleteAllActive(@RequestParam UUID subjectId) {
        questionService.deleteAllActive(subjectId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/drafts/bulk-approve")
    public ResponseEntity<?> approveAllDrafts(@RequestParam UUID subjectId) {
        try {
            questionService.approveAllDrafts(subjectId);
            return ResponseEntity.ok().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/{id}/ungroup")
    public ResponseEntity<Void> ungroupQuestion(@PathVariable UUID id) {
        try {
            questionService.ungroupQuestion(id);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/contexts/{contextId}/ungroup")
    public ResponseEntity<Void> ungroupContext(@PathVariable UUID contextId) {
        questionService.ungroupContext(contextId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/link")
    public ResponseEntity<Question> linkQuestion(@PathVariable UUID id, @RequestBody ContextLinkRequest req) {
        try {
            return ResponseEntity.ok(questionService.linkQuestion(id, req));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/contexts/{contextId}/approve")
    public ResponseEntity<?> approveContextGroup(@PathVariable UUID contextId) {
        try {
            questionService.approveContextGroup(contextId);
            return ResponseEntity.ok().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
