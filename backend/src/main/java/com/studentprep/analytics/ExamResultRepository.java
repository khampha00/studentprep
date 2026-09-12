package com.studentprep.analytics;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ExamResultRepository extends JpaRepository<ExamResult, UUID> {
    long count();

    @Query("SELECT AVG(CAST(e.totalScore AS double) / e.maxScore) * 100 FROM ExamResult e WHERE e.maxScore > 0")
    Double getAverageScorePercentage();

    @Query("SELECT COUNT(e) FROM ExamResult e WHERE e.maxScore > 0 AND (CAST(e.totalScore AS double) / e.maxScore) > 0.5")
    long countPassedExams();

    @Query("SELECT e FROM ExamResult e JOIN FETCH e.student")
    java.util.List<ExamResult> findAllWithStudent();

    @Query("SELECT AVG(CAST(e.totalScore AS double) / e.maxScore) * 100 FROM ExamResult e WHERE e.maxScore > 0 AND e.examSessionId = :examId")
    Double getAverageScorePercentageByExamId(@org.springframework.data.repository.query.Param("examId") UUID examId);

    @Query("SELECT MAX(CAST(e.totalScore AS double) / e.maxScore) * 100 FROM ExamResult e WHERE e.maxScore > 0 AND e.examSessionId = :examId")
    Double getMaxScorePercentageByExamId(@org.springframework.data.repository.query.Param("examId") UUID examId);

    @Query("SELECT MIN(CAST(e.totalScore AS double) / e.maxScore) * 100 FROM ExamResult e WHERE e.maxScore > 0 AND e.examSessionId = :examId")
    Double getMinScorePercentageByExamId(@org.springframework.data.repository.query.Param("examId") UUID examId);

    @Query("SELECT COUNT(e) FROM ExamResult e WHERE e.maxScore > 0 AND (CAST(e.totalScore AS double) / e.maxScore) > 0.5 AND e.examSessionId = :examId")
    long countPassedExamsByExamId(@org.springframework.data.repository.query.Param("examId") UUID examId);

    long countByExamSessionId(UUID examSessionId);
}
