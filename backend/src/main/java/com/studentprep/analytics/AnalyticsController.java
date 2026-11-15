package com.studentprep.analytics;

import com.studentprep.common.ApiResponse;
import com.studentprep.common.PagedResponse;
import com.studentprep.common.TableQueryUtils;
import com.studentprep.exam.ExamInternalAPI;
import com.studentprep.student.Student;
import com.studentprep.student.StudentInternalAPI;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/admin/analytics")
public class AnalyticsController {

    private static final int MAX_RESULTS_PAGE_SIZE = 200;
    private static final Map<String, String> RESULT_SORTABLE_FIELDS = Map.of(
            "studentName", "student.name",
            "registrationNumber", "student.registrationNumber",
            "totalScore", "totalScore",
            "moderationStatus", "moderationStatus",
            "gradedAt", "gradedAt");

    private final StudentInternalAPI studentInternalAPI;
    private final ExamResultRepository examResultRepository;
    private final LeaderboardService leaderboardService;
    private final ExamInternalAPI examInternalAPI;

    public AnalyticsController(
            StudentInternalAPI studentInternalAPI,
            ExamResultRepository examResultRepository,
            LeaderboardService leaderboardService,
            ExamInternalAPI examInternalAPI) {
        this.studentInternalAPI = studentInternalAPI;
        this.examResultRepository = examResultRepository;
        this.leaderboardService = leaderboardService;
        this.examInternalAPI = examInternalAPI;
    }

    @GetMapping("/dashboard")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDashboard() {
        long totalStudents = studentInternalAPI.countStudents();
        long completedExams = examResultRepository.countOfficialCompletedExams();
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

    public ResponseEntity<PagedResponse<List<Map<String, Object>>>> getResults(int page, int size) {
        return getResults(page, size, null, null, null, null);
    }

    @GetMapping("/results")
    public ResponseEntity<PagedResponse<List<Map<String, Object>>>> getResults(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String moderationStatus,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir) {
        if (page < 0 || size < 1 || size > MAX_RESULTS_PAGE_SIZE) {
            throw new IllegalArgumentException("Page must be >= 0 and size must be between 1 and " + MAX_RESULTS_PAGE_SIZE);
        }

        String pattern = TableQueryUtils.toContainsPattern(search);
        ExamResultModerationStatus statusFilter = parseModerationStatus(moderationStatus);
        boolean hasSort = sortBy != null && !sortBy.isBlank();

        Pageable pageable = hasSort
                ? TableQueryUtils.buildPageable(
                        page,
                        size,
                        sortBy,
                        sortDir,
                        RESULT_SORTABLE_FIELDS,
                        "gradedAt",
                        Sort.Direction.DESC,
                        "id")
                : PageRequest.of(page, size);

        Page<ExamResult> resultsPage;
        if (pattern != null && statusFilter != null) {
            resultsPage = examResultRepository.searchByModerationStatusWithStudent(pattern, statusFilter, pageable);
        } else if (pattern != null) {
            resultsPage = examResultRepository.searchWithStudent(pattern, pageable);
        } else if (statusFilter != null) {
            resultsPage = examResultRepository.findByModerationStatusWithStudent(statusFilter, pageable);
        } else {
            resultsPage = examResultRepository.findAllWithStudent(pageable);
        }

        List<Map<String, Object>> results = resultsPage.getContent().stream()
                .map(r -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", r.getId());
                    map.put("examSessionId", r.getExamSessionId());
                    map.put("studentName", r.getStudent() != null ? r.getStudent().getName() : "Unknown");
                    map.put("registrationNumber", r.getStudent() != null ? r.getStudent().getRegistrationNumber() : "");
                    map.put("totalScore", r.getTotalScore());
                    map.put("maxScore", r.getMaxScore());
                    map.put("topicBreakdown", r.getTopicBreakdown() != null ? r.getTopicBreakdown() : Collections.emptyMap());
                    map.put("gradedAt", r.getGradedAt() != null ? r.getGradedAt().toString() : "");
                    String modStatus = r.getModerationStatus() != null ? r.getModerationStatus().name() : "ELIGIBLE";
                    map.put("moderationStatus", modStatus);
                    String sessionStatus = (r.getModerationStatus() == ExamResultModerationStatus.PENDING_REVIEW)
                            ? "LATE_SUBMISSION_FLAGGED"
                            : "SUBMITTED";
                    map.put("sessionStatus", sessionStatus);
                    map.put("answerReceiptCode", examInternalAPI.getSessionAnswerReceiptCode(r.getExamSessionId()));
                    return map;
                }).collect(Collectors.toList());

        return ResponseEntity.ok(PagedResponse.of(
                results,
                resultsPage.getNumber(),
                resultsPage.getSize(),
                resultsPage.getTotalElements(),
                resultsPage.getTotalPages()
        ));
    }

    private ExamResultModerationStatus parseModerationStatus(String moderationStatus) {
        if (moderationStatus == null || moderationStatus.isBlank() || "ALL".equalsIgnoreCase(moderationStatus.trim())) {
            return null;
        }
        try {
            return ExamResultModerationStatus.valueOf(moderationStatus.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid moderationStatus filter: " + moderationStatus);
        }
    }

    @GetMapping("/flagged-sessions")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getFlaggedSessions() {
        return ResponseEntity.ok(ApiResponse.of(examInternalAPI.getFlaggedOrLateSessions()));
    }

    @GetMapping("/exam/{examId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getExamStats(@PathVariable UUID examId) {
        long completedExams = examResultRepository.countOfficialCompletedExamsByExamId(examId);
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
        List<Map<String, Object>> leaderboard = leaderboardService.getLeaderboard(topN);
        List<Map<String, Object>> result = leaderboard.stream().map(entry -> {
            Map<String, Object> map = new HashMap<>(entry);
            try {
                UUID studentId = UUID.fromString((String) map.get("studentId"));
                String name = studentInternalAPI.findById(studentId).map(Student::getName).orElse("Unknown");
                map.put("studentName", name);
            } catch (Exception e) {
                map.put("studentName", "Unknown");
            }
            return map;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.of(result));
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

    @GetMapping("/subject-registrations")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getSubjectRegistrations() {
        return ResponseEntity.ok(ApiResponse.of(studentInternalAPI.getSubjectRegistrations()));
    }
}
