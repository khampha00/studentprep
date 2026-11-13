package com.studentprep.analytics;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ExamResultRepository extends JpaRepository<ExamResult, UUID> {
    long count();
    
    List<ExamResult> findByStudentIdOrderByGradedAtDesc(UUID studentId);

    Optional<ExamResult> findFirstByStudentIdOrderByGradedAtDesc(UUID studentId);

    Optional<ExamResult> findByExamSessionId(UUID examSessionId);

    @Query("SELECT AVG(CAST(e.totalScore AS double) / e.maxScore) * 100 FROM ExamResult e WHERE e.maxScore > 0")
    Double getAverageScorePercentage();

    @Query("SELECT COUNT(e) FROM ExamResult e WHERE e.maxScore > 0 AND (CAST(e.totalScore AS double) / e.maxScore) > 0.5")
    long countPassedExams();

    @Query(value = "SELECT e FROM ExamResult e JOIN FETCH e.student", countQuery = "SELECT COUNT(e) FROM ExamResult e")
    Page<ExamResult> findAllWithStudent(Pageable pageable);

    @Query("SELECT AVG(CAST(e.totalScore AS double) / e.maxScore) * 100 FROM ExamResult e WHERE e.maxScore > 0 AND e.examSessionId = :examId")
    Double getAverageScorePercentageByExamId(@Param("examId") UUID examId);

    @Query("SELECT MAX(CAST(e.totalScore AS double) / e.maxScore) * 100 FROM ExamResult e WHERE e.maxScore > 0 AND e.examSessionId = :examId")
    Double getMaxScorePercentageByExamId(@Param("examId") UUID examId);

    @Query("SELECT MIN(CAST(e.totalScore AS double) / e.maxScore) * 100 FROM ExamResult e WHERE e.maxScore > 0 AND e.examSessionId = :examId")
    Double getMinScorePercentageByExamId(@Param("examId") UUID examId);

    @Query("SELECT COUNT(e) FROM ExamResult e WHERE e.maxScore > 0 AND (CAST(e.totalScore AS double) / e.maxScore) > 0.5 AND e.examSessionId = :examId")
    long countPassedExamsByExamId(@Param("examId") UUID examId);

    long countByExamSessionId(UUID examSessionId);
}
