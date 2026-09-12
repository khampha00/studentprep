package com.studentprep.questionbank;

import com.studentprep.questionbank.dto.ContextLinkRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;
import com.studentprep.common.ApiResponse;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/questions")
public class QuestionController {

    private final QuestionService questionService;

    public QuestionController(QuestionService questionService) {
        this.questionService = questionService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Question>>> getQuestionsByStatus(
            @RequestParam(defaultValue = "DRAFT") String status,
            @RequestParam(required = false) UUID subjectId) {
        return ResponseEntity.ok(ApiResponse.of(questionService.getQuestions(status, subjectId)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<Question>> updateQuestionStatus(@PathVariable UUID id, @RequestBody Question updateRequest) {
        try {
            return ResponseEntity.ok(ApiResponse.of(questionService.updateQuestionStatus(id, updateRequest)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, String>>> deleteQuestion(@PathVariable UUID id) {
        try {
            questionService.deleteQuestion(id);
            return ResponseEntity.ok(ApiResponse.of(Map.of("status", "DELETED")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/drafts/bulk")
    public ResponseEntity<ApiResponse<Map<String, String>>> deleteDraftsBulk(@RequestParam UUID subjectId) {
        questionService.deleteDraftsBulk(subjectId);
        return ResponseEntity.ok(ApiResponse.of(Map.of("status", "DELETED")));
    }

    @DeleteMapping("/active/bulk")
    public ResponseEntity<ApiResponse<Map<String, String>>> deleteAllActive(@RequestParam UUID subjectId) {
        questionService.deleteAllActive(subjectId);
        return ResponseEntity.ok(ApiResponse.of(Map.of("status", "DELETED")));
    }

    @PostMapping("/drafts/bulk-approve")
    public ResponseEntity<ApiResponse<Map<String, String>>> approveAllDrafts(@RequestParam UUID subjectId) {
        try {
            questionService.approveAllDrafts(subjectId);
            return ResponseEntity.ok(ApiResponse.of(Map.of("status", "SUCCESS")));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(ApiResponse.of(Map.of("error", e.getMessage())));
        }
    }

    @PostMapping("/{id}/ungroup")
    public ResponseEntity<ApiResponse<Map<String, String>>> ungroupQuestion(@PathVariable UUID id) {
        try {
            questionService.ungroupQuestion(id);
            return ResponseEntity.ok(ApiResponse.of(Map.of("status", "SUCCESS")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/contexts/{contextId}/ungroup")
    public ResponseEntity<ApiResponse<Map<String, String>>> ungroupContext(@PathVariable UUID contextId) {
        questionService.ungroupContext(contextId);
        return ResponseEntity.ok(ApiResponse.of(Map.of("status", "SUCCESS")));
    }

    @PostMapping("/{id}/link")
    public ResponseEntity<ApiResponse<Question>> linkQuestion(@PathVariable UUID id, @RequestBody ContextLinkRequest req) {
        try {
            return ResponseEntity.ok(ApiResponse.of(questionService.linkQuestion(id, req)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/contexts/{contextId}/approve")
    public ResponseEntity<ApiResponse<Map<String, String>>> approveContextGroup(@PathVariable UUID contextId) {
        try {
            questionService.approveContextGroup(contextId);
            return ResponseEntity.ok(ApiResponse.of(Map.of("status", "SUCCESS")));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(ApiResponse.of(Map.of("error", e.getMessage())));
        }
    }
}
