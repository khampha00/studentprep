package com.studentprep.questionbank;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface QuestionContextRepository extends JpaRepository<QuestionContext, UUID> {
    
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM QuestionContext c WHERE NOT EXISTS (SELECT q FROM Question q WHERE q.context = c)")
    void deleteOrphanedContexts();
}
