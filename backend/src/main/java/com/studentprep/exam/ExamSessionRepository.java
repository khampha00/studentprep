package com.studentprep.exam;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;
public interface ExamSessionRepository extends JpaRepository<ExamSession, UUID> {
    Optional<ExamSession> findByUserIdAndStatus(UUID userId, String status);
    long countByStatus(String status);
    java.util.List<ExamSession> findAllByStatus(String status);
}
