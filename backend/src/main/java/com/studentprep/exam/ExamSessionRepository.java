package com.studentprep.exam;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface ExamSessionRepository extends JpaRepository<ExamSession, UUID> {}
