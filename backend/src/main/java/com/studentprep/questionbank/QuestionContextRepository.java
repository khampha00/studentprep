package com.studentprep.questionbank;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Repository
public interface QuestionContextRepository extends JpaRepository<QuestionContext, UUID> {
    
    @Transactional
    @Modifying
    @Query("DELETE FROM QuestionContext context WHERE NOT EXISTS (SELECT question FROM Question question WHERE question.context = context)")
    void deleteOrphanedContexts();
}
