package com.studentprep.questionbank;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface QuestionContextRepository extends JpaRepository<QuestionContext, UUID> {
}
