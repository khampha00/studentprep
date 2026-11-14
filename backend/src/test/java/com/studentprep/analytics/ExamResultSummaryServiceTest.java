package com.studentprep.analytics;

import com.studentprep.analytics.ExamResultSummaryService.ExamResultSlipData;
import com.studentprep.questionbank.Subject;
import com.studentprep.student.Student;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExamResultSummaryServiceTest {

    private final ExamResultSummaryService summaryService = new ExamResultSummaryService();

    @Test
    void computeSubjectScores_AggregatesTopicKeysBySubjectPrefixAndPreservesOrder() {
        Map<String, TopicStats> topics = new LinkedHashMap<>();
        topics.put("Physics - Mechanics", new TopicStats(8, 10));
        topics.put("Physics - Optics", new TopicStats(7, 10));
        topics.put("Biology - Genetics", new TopicStats(15, 20));
        topics.put("General", new TopicStats(4, 5));

        ExamResult result = new ExamResult();
        result.setTotalScore(34);
        result.setMaxScore(45);
        result.setTopicBreakdown(topics);

        Map<String, int[]> subjectScores = summaryService.computeSubjectScores(result);

        assertThat(subjectScores.keySet()).containsExactly("Physics", "Biology", "General");
        assertThat(subjectScores.get("Physics")).containsExactly(15, 20);
        assertThat(subjectScores.get("Biology")).containsExactly(15, 20);
        assertThat(subjectScores.get("General")).containsExactly(4, 5);
    }

    @Test
    void computeSubjectScores_FallsBackToStudentSubjectsWhenTopicBreakdownMissing() {
        Subject chemistry = new Subject("Chemistry");
        Student student = new Student();
        student.setName("Ada Lovelace");
        student.setRegistrationNumber("123456JF");
        student.setSubjects(Set.of(chemistry));

        ExamResult result = new ExamResult();
        result.setStudent(student);
        result.setTotalScore(0);
        result.setMaxScore(0);

        Map<String, int[]> subjectScores = summaryService.computeSubjectScores(result);

        assertThat(subjectScores).containsOnlyKeys("Chemistry");
        assertThat(subjectScores.get("Chemistry")).containsExactly(0, 0);
    }

    @Test
    void summarize_ComputesPercentagePassStatusAndFallbackGeneralExam() {
        UUID resultId = UUID.fromString("12345678-1234-1234-1234-1234567890ab");
        Instant gradedAt = Instant.parse("2026-11-14T10:15:30Z");

        Student student = new Student();
        student.setName("Chidi Okonkwo");
        student.setRegistrationNumber("654321AB");

        ExamResult passingResult = new ExamResult();
        passingResult.setId(resultId);
        passingResult.setStudent(student);
        passingResult.setTotalScore(50);
        passingResult.setMaxScore(100);
        passingResult.setGradedAt(gradedAt);

        ExamResultSlipData passSummary = summaryService.summarize(passingResult);

        assertThat(passSummary.resultId()).isEqualTo(resultId);
        assertThat(passSummary.candidateName()).isEqualTo("Chidi Okonkwo");
        assertThat(passSummary.registrationNumber()).isEqualTo("654321AB");
        assertThat(passSummary.overallPercentage()).isEqualTo(50.0);
        assertThat(passSummary.passed()).isTrue();
        assertThat(passSummary.subjectScores())
                .singleElement()
                .satisfies(subject -> {
                    assertThat(subject.subjectName()).isEqualTo("General Examination");
                    assertThat(subject.score()).isEqualTo(50);
                    assertThat(subject.maxScore()).isEqualTo(100);
                    assertThat(subject.percentage()).isEqualTo(50.0);
                });

        ExamResult failingResult = new ExamResult();
        failingResult.setTotalScore(0);
        failingResult.setMaxScore(0);
        ExamResultSlipData failSummary = summaryService.summarize(failingResult);
        assertThat(failSummary.overallPercentage()).isEqualTo(0.0);
        assertThat(failSummary.passed()).isFalse();
        assertThat(failSummary.candidateName()).isEqualTo("N/A");
    }

    @Test
    void summarize_RejectsNullExamResult() {
        assertThatThrownBy(() -> summaryService.summarize(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be null");
    }
}
