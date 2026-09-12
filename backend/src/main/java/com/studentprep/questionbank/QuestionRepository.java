package com.studentprep.questionbank;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface QuestionRepository extends JpaRepository<Question, UUID> {
    List<Question> findByStatusOrderByCreatedAtAsc(String status);
    List<Question> findByStatusAndSubjectIdOrderByCreatedAtAsc(String status, UUID subjectId);
    boolean existsBySubjectId(UUID subjectId);
    
    @Transactional
    @Modifying
    @Query("DELETE FROM Question question WHERE question.status = ?1 AND question.subject.id = ?2")
    void deleteByStatusAndSubjectId(String status, UUID subjectId);
    
    long countByContextId(UUID contextId);
    List<Question> findByContextId(UUID contextId);
    List<Question> findByStatusAndSubjectIdIn(String status, List<UUID> subjectIds);
}
