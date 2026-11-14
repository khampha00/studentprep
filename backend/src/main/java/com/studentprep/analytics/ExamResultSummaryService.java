package com.studentprep.analytics;

import com.studentprep.questionbank.Subject;
import com.studentprep.student.Student;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Computes subject-level breakdowns and candidate performance summaries from {@link ExamResult}
 * domain entities independently of PDF rendering concerns.
 */
@Service
public class ExamResultSummaryService {

    public static final double DEFAULT_PASS_MARK_PERCENTAGE = 50.0;

    /**
     * Aggregates topic breakdown into subject scores: {@code subjectName -> [score, maxScore]}.
     */
    public Map<String, int[]> computeSubjectScores(ExamResult result) {
        if (result == null) {
            throw new IllegalArgumentException("Exam result cannot be null");
        }

        Map<String, int[]> subjectScores = new LinkedHashMap<>();

        if (result.getTopicBreakdown() != null && !result.getTopicBreakdown().isEmpty()) {
            for (Map.Entry<String, TopicStats> entry : result.getTopicBreakdown().entrySet()) {
                String key = entry.getKey();
                if (key == null) {
                    continue;
                }
                String subject = extractSubjectName(key);
                TopicStats stats = entry.getValue();
                if (stats != null) {
                    int[] bucket = subjectScores.computeIfAbsent(subject, k -> new int[2]);
                    bucket[0] += stats.getCorrect();
                    bucket[1] += stats.getTotal();
                }
            }
        }

        // If no topic breakdown exists yet, fall back to the student's enrolled subjects.
        if (subjectScores.isEmpty() && result.getStudent() != null && result.getStudent().getSubjects() != null) {
            for (Subject s : result.getStudent().getSubjects()) {
                subjectScores.put(s.getName(), new int[]{0, 0});
            }
        }

        if (subjectScores.isEmpty()) {
            int total = safeInt(result.getTotalScore());
            int max = safeInt(result.getMaxScore());
            subjectScores.put("General Examination", new int[]{total, max});
        }

        return subjectScores;
    }

    /**
     * Builds an immutable presentation-ready summary from a graded {@link ExamResult}.
     */
    public ExamResultSlipData summarize(ExamResult result) {
        if (result == null) {
            throw new IllegalArgumentException("Exam result cannot be null");
        }

        Student student = result.getStudent();
        String candidateName = student != null && student.getName() != null ? student.getName() : "N/A";
        String registrationNumber = student != null && student.getRegistrationNumber() != null
                ? student.getRegistrationNumber()
                : "N/A";

        int totalScore = safeInt(result.getTotalScore());
        int maxScore = safeInt(result.getMaxScore());
        double overallPercentage = calculatePercentage(totalScore, maxScore);
        boolean passed = overallPercentage >= DEFAULT_PASS_MARK_PERCENTAGE;

        List<SubjectScore> subjectBreakdowns = computeSubjectScores(result).entrySet().stream()
                .map(entry -> new SubjectScore(
                        entry.getKey(),
                        entry.getValue()[0],
                        entry.getValue()[1],
                        calculatePercentage(entry.getValue()[0], entry.getValue()[1])))
                .toList();

        return new ExamResultSlipData(
                result.getId(),
                candidateName,
                registrationNumber,
                result.getGradedAt(),
                totalScore,
                maxScore,
                overallPercentage,
                passed,
                subjectBreakdowns);
    }

    private static String extractSubjectName(String topicKey) {
        int separatorIdx = topicKey.indexOf(" - ");
        return separatorIdx >= 0 ? topicKey.substring(0, separatorIdx).trim() : topicKey;
    }

    private static int safeInt(Integer value) {
        return value != null ? value : 0;
    }

    private static double calculatePercentage(int score, int maxScore) {
        return maxScore > 0 ? ((double) score / maxScore) * 100.0 : 0.0;
    }

    /**
     * Immutable per-subject score summary for result slips and analytics views.
     */
    public record SubjectScore(String subjectName, int score, int maxScore, double percentage) {
    }

    /**
     * Immutable view model decoupling exam result rendering from JPA entities and lazy collections.
     */
    public record ExamResultSlipData(
            UUID resultId,
            String candidateName,
            String registrationNumber,
            Instant gradedAt,
            int totalScore,
            int maxScore,
            double overallPercentage,
            boolean passed,
            List<SubjectScore> subjectScores) {
    }
}
