package com.studentprep.questionbank;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;
public interface QuestionRepository extends JpaRepository<Question, UUID> {
    List<Question> findByStatusOrderByCreatedAtAsc(String status);
    List<Question> findByStatusAndSubjectIdOrderByCreatedAtAsc(String status, UUID subjectId);
    boolean existsBySubjectId(UUID subjectId);
    
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM Question q WHERE q.status = ?1 AND q.subject.id = ?2")
    void deleteByStatusAndSubjectId(String status, UUID subjectId);
}
