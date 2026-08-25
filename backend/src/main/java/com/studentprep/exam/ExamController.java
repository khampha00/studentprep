package com.studentprep.exam;

import com.studentprep.exam.dto.ExamStartResponse;
import com.studentprep.exam.dto.ExamSyncRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/exams")
public class ExamController {

    private final ExamService examService;

    public ExamController(ExamService examService) {
        this.examService = examService;
    }
    
    @GetMapping("/active/payload")
    public ResponseEntity<java.util.Map<String, Object>> getActivePayload() {
        java.util.Map<String, Object> wrapper = new java.util.HashMap<>();
        wrapper.put("data", examService.getActivePayload());
        return ResponseEntity.ok(wrapper);
    }
    
    @PostMapping("/start")
    public ResponseEntity<ExamStartResponse> startExam(@RequestParam UUID userId) {
        return ResponseEntity.ok(examService.startExam(userId));
    }

    @PostMapping("/{sessionId}/sync")
    public ResponseEntity<Void> syncExam(@PathVariable UUID sessionId, @RequestBody ExamSyncRequest request) {
        examService.syncExam(sessionId, request);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{sessionId}/submit")
    public ResponseEntity<Void> submitExam(@PathVariable UUID sessionId) {
        examService.submitExam(sessionId);
        return ResponseEntity.ok().build();
    }
}
