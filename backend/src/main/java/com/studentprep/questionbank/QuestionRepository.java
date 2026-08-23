package com.studentprep.questionbank;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;
public interface QuestionRepository extends JpaRepository<Question, UUID> {
    List<Question> findByStatus(String status);
}
