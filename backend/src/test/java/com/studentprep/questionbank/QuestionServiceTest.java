package com.studentprep.questionbank;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import java.util.Optional;
import java.util.UUID;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class QuestionServiceTest {

    @Mock
    private QuestionRepository questionRepository;

    @Mock
    private QuestionContextRepository contextRepository;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @InjectMocks
    private QuestionService questionService;

    @Test
    void testGetQuestions_Success() {
        UUID subjectId = UUID.randomUUID();
        Question q = new Question();
        q.setId(UUID.randomUUID());
        when(questionRepository.findByStatusAndSubject_IdOrderByCreatedAtAsc(QuestionStatus.ACTIVE, subjectId)).thenReturn(List.of(q));

        List<Question> result = questionService.getQuestions("ACTIVE", subjectId);
        
        assertNotNull(result);
        assertEquals(1, result.size());
    }

    @Test
    void testGetQuestions_InvalidStatus() {
        List<Question> result = questionService.getQuestions("INVALID", null);
        assertTrue(result.isEmpty());
    }
}
