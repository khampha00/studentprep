package com.studentprep.exam;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;
import java.util.List;

public interface ExamSessionRepository extends JpaRepository<ExamSession, UUID> {
    Optional<ExamSession> findByUserIdAndStatus(UUID userId, String status);
    boolean existsByUserIdAndStatus(UUID userId, String status);
    List<ExamSession> findAllByUserIdAndStatus(UUID userId, String status);
    long countByStatus(String status);
    List<ExamSession> findAllByStatus(String status);
}

