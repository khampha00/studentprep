package com.studentprep.analytics;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ExamResultRepository extends JpaRepository<ExamResult, UUID> {

    long count();

    List<ExamResult> findByStudentIdOrderByGradedAtDesc(UUID studentId);

    Optional<ExamResult> findFirstByStudentIdOrderByGradedAtDesc(UUID studentId);

    Optional<ExamResult> findByExamSessionId(UUID examSessionId);

    @Query("SELECT COUNT(e) FROM ExamResult e WHERE e.moderationStatus IN ('ELIGIBLE', 'CLEARED')")
    long countOfficialCompletedExams();

    @Query("SELECT COUNT(e) FROM ExamResult e WHERE e.examSessionId = :examId AND e.moderationStatus IN ('ELIGIBLE', 'CLEARED')")
    long countOfficialCompletedExamsByExamId(@Param("examId") UUID examId);

    @Query("SELECT AVG(CAST(e.totalScore AS double) / e.maxScore) * 100 FROM ExamResult e WHERE e.maxScore > 0 AND e.moderationStatus IN ('ELIGIBLE', 'CLEARED')")
    Double getAverageScorePercentage();

    @Query("SELECT COUNT(e) FROM ExamResult e WHERE e.maxScore > 0 AND (CAST(e.totalScore AS double) / e.maxScore) >= 0.5 AND e.moderationStatus IN ('ELIGIBLE', 'CLEARED')")
    long countPassedExams();

    @Query(value = "SELECT e FROM ExamResult e JOIN FETCH e.student", countQuery = "SELECT COUNT(e) FROM ExamResult e")
    Page<ExamResult> findAllWithStudent(Pageable pageable);

    @Query(
            value = "SELECT e FROM ExamResult e JOIN FETCH e.student WHERE e.moderationStatus = :status",
            countQuery = "SELECT COUNT(e) FROM ExamResult e WHERE e.moderationStatus = :status")
    Page<ExamResult> findByModerationStatusWithStudent(
            @Param("status") ExamResultModerationStatus status,
            Pageable pageable);

    @Query(
            value = """
                    SELECT e FROM ExamResult e JOIN FETCH e.student s
                    WHERE LOWER(s.name) LIKE :pattern ESCAPE '!'
                       OR LOWER(s.registrationNumber) LIKE :pattern ESCAPE '!'
                    """,
            countQuery = """
                    SELECT COUNT(e) FROM ExamResult e JOIN e.student s
                    WHERE LOWER(s.name) LIKE :pattern ESCAPE '!'
                       OR LOWER(s.registrationNumber) LIKE :pattern ESCAPE '!'
                    """)
    Page<ExamResult> searchWithStudent(@Param("pattern") String pattern, Pageable pageable);

    @Query(
            value = """
                    SELECT e FROM ExamResult e JOIN FETCH e.student s
                    WHERE e.moderationStatus = :status
                      AND (LOWER(s.name) LIKE :pattern ESCAPE '!'
                           OR LOWER(s.registrationNumber) LIKE :pattern ESCAPE '!')
                    """,
            countQuery = """
                    SELECT COUNT(e) FROM ExamResult e JOIN e.student s
                    WHERE e.moderationStatus = :status
                      AND (LOWER(s.name) LIKE :pattern ESCAPE '!'
                           OR LOWER(s.registrationNumber) LIKE :pattern ESCAPE '!')
                    """)
    Page<ExamResult> searchByModerationStatusWithStudent(
            @Param("pattern") String pattern,
            @Param("status") ExamResultModerationStatus status,
            Pageable pageable);

    @Query("SELECT AVG(CAST(e.totalScore AS double) / e.maxScore) * 100 FROM ExamResult e WHERE e.maxScore > 0 AND e.examSessionId = :examId AND e.moderationStatus IN ('ELIGIBLE', 'CLEARED')")
    Double getAverageScorePercentageByExamId(@Param("examId") UUID examId);

    @Query("SELECT MAX(CAST(e.totalScore AS double) / e.maxScore) * 100 FROM ExamResult e WHERE e.maxScore > 0 AND e.examSessionId = :examId AND e.moderationStatus IN ('ELIGIBLE', 'CLEARED')")
    Double getMaxScorePercentageByExamId(@Param("examId") UUID examId);

    @Query("SELECT MIN(CAST(e.totalScore AS double) / e.maxScore) * 100 FROM ExamResult e WHERE e.maxScore > 0 AND e.examSessionId = :examId AND e.moderationStatus IN ('ELIGIBLE', 'CLEARED')")
    Double getMinScorePercentageByExamId(@Param("examId") UUID examId);

    @Query("SELECT COUNT(e) FROM ExamResult e WHERE e.maxScore > 0 AND (CAST(e.totalScore AS double) / e.maxScore) >= 0.5 AND e.examSessionId = :examId AND e.moderationStatus IN ('ELIGIBLE', 'CLEARED')")
    long countPassedExamsByExamId(@Param("examId") UUID examId);

    long countByExamSessionId(UUID examSessionId);

    long countByModerationStatusIn(Collection<ExamResultModerationStatus> statuses);
}
