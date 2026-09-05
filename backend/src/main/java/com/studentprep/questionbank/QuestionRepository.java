package com.studentprep.questionbank;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;
public interface QuestionRepository extends JpaRepository<Question, UUID> {
    List<Question> findByStatusOrderByCreatedAtAsc(String status);
    List<Question> findByStatusAndSubjectIdOrderByCreatedAtAsc(String status, UUID subjectId);
    boolean existsBySubjectId(UUID subjectId);
}
