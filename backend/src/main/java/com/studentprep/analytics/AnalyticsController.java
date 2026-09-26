package com.studentprep.analytics;

import com.studentprep.common.ApiResponse;
import com.studentprep.exam.ExamInternalAPI;
import com.studentprep.student.StudentInternalAPI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/admin/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final StudentInternalAPI studentInternalAPI;
    private final ExamResultRepository examResultRepository;
    private final LeaderboardService leaderboardService;
    private final ExamInternalAPI examInternalAPI;

    @GetMapping("/dashboard")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDashboard() {
        long totalStudents = studentInternalAPI.countStudents();
        long completedExams = examResultRepository.count();
        Double averageScore = examResultRepository.getAverageScorePercentage();
        long passedExams = examResultRepository.countPassedExams();
        
        double passRate = completedExams > 0 ? ((double) passedExams / completedExams) * 100.0 : 0.0;
        
        return ResponseEntity.ok(ApiResponse.of(Map.of(
                "totalStudents", totalStudents,
                "completedExams", completedExams,
                "averageScore", averageScore != null ? averageScore : 0.0,
                "passRate", passRate
        )));
    }

    @GetMapping("/results")
    public ResponseEntity<com.studentprep.common.PagedResponse<List<Map<String, Object>>>> getResults(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        org.springframework.data.domain.Page<ExamResult> resultsPage = examResultRepository.findAllWithStudent(org.springframework.data.domain.PageRequest.of(page, size));
        List<Map<String, Object>> results = resultsPage.getContent().stream()
                .map(r -> Map.<String, Object>of(
                        "id", r.getId(),
                        "studentName", r.getStudent().getName(),
                        "totalScore", r.getTotalScore(),
                        "maxScore", r.getMaxScore(),
                        "topicBreakdown", r.getTopicBreakdown(),
                        "gradedAt", r.getGradedAt() != null ? r.getGradedAt().toString() : ""
                )).collect(Collectors.toList());
        return ResponseEntity.ok(com.studentprep.common.PagedResponse.of(results, resultsPage.getNumber(), resultsPage.getSize(), resultsPage.getTotalElements(), resultsPage.getTotalPages()));
    }

    @GetMapping("/exam/{examId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getExamStats(@PathVariable UUID examId) {
        long completedExams = examResultRepository.countByExamSessionId(examId);
        Double averageScore = examResultRepository.getAverageScorePercentageByExamId(examId);
        Double maxScore = examResultRepository.getMaxScorePercentageByExamId(examId);
        Double minScore = examResultRepository.getMinScorePercentageByExamId(examId);
        long passedExams = examResultRepository.countPassedExamsByExamId(examId);
        
        double passRate = completedExams > 0 ? ((double) passedExams / completedExams) * 100.0 : 0.0;
        
        return ResponseEntity.ok(ApiResponse.of(Map.of(
                "examId", examId,
                "completedExams", completedExams,
                "averageScore", averageScore != null ? averageScore : 0.0,
                "maxScore", maxScore != null ? maxScore : 0.0,
                "minScore", minScore != null ? minScore : 0.0,
                "passRate", passRate
        )));
    }

    @GetMapping("/leaderboard")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getLeaderboard(@RequestParam(defaultValue = "10") int topN) {
        return ResponseEntity.ok(ApiResponse.of(leaderboardService.getLeaderboard(topN)));
    }

    @GetMapping("/live-sessions")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getLiveSessions() {
        return ResponseEntity.ok(ApiResponse.of(examInternalAPI.getLiveSessions()));
    }

    @GetMapping("/subjects")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getSubjects() {
        List<ExamResult> allResults = examResultRepository.findAll();
        Map<String, int[]> subjectAggregates = new HashMap<>();
        
        for (ExamResult result : allResults) {
            if (result.getTopicBreakdown() != null) {
                for (Map.Entry<String, TopicStats> entry : result.getTopicBreakdown().entrySet()) {
                    int separatorIndex = entry.getKey().indexOf(" - ");
                    String subjectName = separatorIndex > 0 ? entry.getKey().substring(0, separatorIndex) : entry.getKey();
                    TopicStats stats = entry.getValue();
                    
                    subjectAggregates.putIfAbsent(subjectName, new int[]{0, 0});
                    subjectAggregates.get(subjectName)[0] += stats.getCorrect();
                    subjectAggregates.get(subjectName)[1] += stats.getTotal();
                }
            }
        }
        
        List<Map<String, Object>> subjects = subjectAggregates.entrySet().stream()
            .map(entry -> {
                String subjectName = entry.getKey();
                int correct = entry.getValue()[0];
                int total = entry.getValue()[1];
                double averageScore = total > 0 ? ((double) correct / total) * 100.0 : 0.0;
                return Map.<String, Object>of(
                    "subjectName", subjectName,
                    "averageScore", averageScore
                );
            })
            .collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.of(subjects));
    }
}
