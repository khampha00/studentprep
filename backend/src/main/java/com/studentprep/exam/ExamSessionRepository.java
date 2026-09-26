package com.studentprep.exam;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;
import java.util.List;

public interface ExamSessionRepository extends JpaRepository<ExamSession, UUID> {
    Optional<ExamSession> findByUserIdAndStatus(UUID userId, ExamSessionStatus status);
    boolean existsByUserIdAndStatus(UUID userId, ExamSessionStatus status);
    List<ExamSession> findAllByUserIdAndStatus(UUID userId, ExamSessionStatus status);
    long countByStatus(ExamSessionStatus status);
    List<ExamSession> findAllByStatus(ExamSessionStatus status);
}

