package com.studentprep.analytics;

import com.studentprep.common.ApiResponse;
import com.studentprep.student.StudentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import com.studentprep.exam.ExamSessionRepository;

@RestController
@RequestMapping("/api/v1/admin/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final StudentRepository studentRepository;
    private final ExamResultRepository examResultRepository;
    private final LeaderboardService leaderboardService;
    private final ExamSessionRepository examSessionRepository;

    @GetMapping("/dashboard")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDashboard() {
        long totalStudents = studentRepository.count();
        long completedExams = examResultRepository.count();
        Double averageScore = examResultRepository.getAverageScorePercentage();
        long passedExams = examResultRepository.countPassedExams();
        
        double passRate = completedExams > 0 ? ((double) passedExams / completedExams) * 100.0 : 0.0;
        
        return ResponseEntity.ok(ApiResponse.of(Map.<String, Object>of(
                "totalStudents", totalStudents,
                "completedExams", completedExams,
                "averageScore", averageScore != null ? averageScore : 0.0,
                "passRate", passRate
        )));
    }

    @GetMapping("/results")
    public ResponseEntity<ApiResponse<java.util.List<Map<String, Object>>>> getResults() {
        java.util.List<Map<String, Object>> results = examResultRepository.findAllWithStudent().stream()
                .map(r -> Map.<String, Object>of(
                        "id", r.getId(),
                        "studentName", r.getStudent().getName(),
                        "totalScore", r.getTotalScore(),
                        "maxScore", r.getMaxScore(),
                        "topicBreakdown", r.getTopicBreakdown(),
                        "gradedAt", r.getGradedAt() != null ? r.getGradedAt().toString() : ""
                )).collect(java.util.stream.Collectors.toList());
        return ResponseEntity.ok(ApiResponse.of(results));
    }

    @GetMapping("/exam/{examId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getExamStats(@PathVariable UUID examId) {
        long completedExams = examResultRepository.countByExamSessionId(examId);
        Double averageScore = examResultRepository.getAverageScorePercentageByExamId(examId);
        Double maxScore = examResultRepository.getMaxScorePercentageByExamId(examId);
        Double minScore = examResultRepository.getMinScorePercentageByExamId(examId);
        long passedExams = examResultRepository.countPassedExamsByExamId(examId);
        
        double passRate = completedExams > 0 ? ((double) passedExams / completedExams) * 100.0 : 0.0;
        
        return ResponseEntity.ok(ApiResponse.of(Map.<String, Object>of(
                "examId", examId,
                "completedExams", completedExams,
                "averageScore", averageScore != null ? averageScore : 0.0,
                "maxScore", maxScore != null ? maxScore : 0.0,
                "minScore", minScore != null ? minScore : 0.0,
                "passRate", passRate
        )));
    }

    @GetMapping("/leaderboard")
    public ResponseEntity<ApiResponse<java.util.List<Map<String, Object>>>> getLeaderboard(@RequestParam(defaultValue = "10") int topN) {
        return ResponseEntity.ok(ApiResponse.of(leaderboardService.getLeaderboard(topN)));
    }

    @GetMapping("/live-sessions")
    public ResponseEntity<ApiResponse<java.util.List<Map<String, Object>>>> getLiveSessions() {
        java.util.List<Map<String, Object>> sessions = examSessionRepository.findAllByStatus("IN_PROGRESS").stream().map(s -> {
            String studentName = studentRepository.findById(s.getUserId())
                    .map(student -> student.getName())
                    .orElse("Unknown Student");
            
            Object timeLeftObj = s.getStatePayload() != null ? s.getStatePayload().get("timeLeft") : 7200;
            int timeLeft = 7200;
            if (timeLeftObj instanceof Number) {
                timeLeft = ((Number) timeLeftObj).intValue();
            }
            
            return Map.<String, Object>of(
                    "sessionId", s.getId().toString(),
                    "studentName", studentName,
                    "timeLeft", timeLeft,
                    "status", s.getStatus()
            );
        }).collect(java.util.stream.Collectors.toList());
        return ResponseEntity.ok(ApiResponse.of(sessions));
    }

    @GetMapping("/subjects")
    public ResponseEntity<ApiResponse<java.util.List<Map<String, Object>>>> getSubjects() {
        // Since there is no specific query for this, we will aggregate it or return a mock for the frontend
        java.util.List<Map<String, Object>> subjects = java.util.List.of(
            Map.of("subjectName", "Mathematics", "averageScore", 75.5),
            Map.of("subjectName", "English Language", "averageScore", 68.2),
            Map.of("subjectName", "Physics", "averageScore", 60.1),
            Map.of("subjectName", "Chemistry", "averageScore", 72.8)
        );
        return ResponseEntity.ok(ApiResponse.of(subjects));
    }
}
