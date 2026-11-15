package com.studentprep.exam;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExamSessionRepository extends JpaRepository<ExamSession, UUID> {
    Optional<ExamSession> findByUserIdAndStatus(UUID userId, ExamSessionStatus status);
    boolean existsByUserIdAndStatus(UUID userId, ExamSessionStatus status);
    List<ExamSession> findAllByUserIdAndStatus(UUID userId, ExamSessionStatus status);
    long countByStatus(ExamSessionStatus status);
    List<ExamSession> findAllByStatus(ExamSessionStatus status);

    Optional<ExamSession> findByUserIdAndStatusAndSupersededFalse(UUID userId, ExamSessionStatus status);
    boolean existsByUserIdAndStatusAndSupersededFalse(UUID userId, ExamSessionStatus status);
    List<ExamSession> findAllByUserIdAndSupersededFalse(UUID userId);

    long countByIdAndStatusIn(UUID id, Collection<ExamSessionStatus> statuses);

    @Query("SELECT s FROM ExamSession s WHERE s.status = 'IN_PROGRESS' AND s.superseded = false AND s.expectedEndTime < :cutoff")
    List<ExamSession> findExpiredInProgressSessions(@Param("cutoff") Instant cutoff);

    @Query("SELECT s FROM ExamSession s WHERE s.superseded = false AND s.status IN ('FLAGGED_TAB_SWITCH', 'LATE_SUBMISSION_FLAGGED', 'TIME_EXPIRED') ORDER BY s.startTime DESC")
    List<ExamSession> findFlaggedOrLateSessions();
}

