package com.studentprep.questionbank;
import org.springframework.stereotype.Service;
import java.util.List;
@Service
public class QuestionService implements QuestionInternalAPI {
    private final QuestionRepository repository;
    public QuestionService(QuestionRepository repository) { this.repository = repository; }
    @Override
    public List<Question> getActiveQuestions() {
        return repository.findByStatusOrderByCreatedAtAsc("ACTIVE");
    }
}
