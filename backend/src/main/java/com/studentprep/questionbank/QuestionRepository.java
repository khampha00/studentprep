package com.studentprep.questionbank;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface QuestionRepository extends JpaRepository<Question, UUID> {
    List<Question> findByStatusOrderByCreatedAtAsc(QuestionStatus status);
    List<Question> findByStatusAndSubject_IdOrderByCreatedAtAsc(QuestionStatus status, UUID subjectId);
    boolean existsBySubject_Id(UUID subjectId);
    
    @Transactional
    @Modifying
    @Query("DELETE FROM Question question WHERE question.status = ?1 AND question.subject.id = ?2")
    void deleteByStatusAndSubject_Id(QuestionStatus status, UUID subjectId);
    
    long countByContext_Id(UUID contextId);
    List<Question> findByContext_Id(UUID contextId);
    List<Question> findByStatusAndSubject_IdIn(QuestionStatus status, List<UUID> subjectIds);
    List<Question> findBySubjectOrderByCreatedAtAsc(Subject subject);
}

