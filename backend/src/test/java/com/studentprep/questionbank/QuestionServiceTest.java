package com.studentprep.questionbank;

import com.studentprep.questionbank.dto.ContextUpdateRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

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

    @Test
    void testUpdateContext_UpdatesPassageAndAssets_AndEvictsCache() {
        UUID ctxId = UUID.randomUUID();
        QuestionContext existing = new QuestionContext();
        existing.setId(ctxId);
        existing.setPassage("Old passage");
        existing.setAssets(List.of());

        when(contextRepository.findById(ctxId)).thenReturn(Optional.of(existing));
        when(contextRepository.save(any(QuestionContext.class))).thenAnswer(inv -> inv.getArgument(0));

        List<Map<String, Object>> newAssets = List.of(Map.of(
                "type", "image",
                "url", "http://localhost:9000/studentprep-assets/diagrams/cell.png"
        ));
        QuestionContext saved = questionService.updateContext(
                ctxId,
                new ContextUpdateRequest("Updated passage", newAssets)
        );

        assertEquals("Updated passage", saved.getPassage());
        assertEquals(1, saved.getAssets().size());
        assertEquals("http://localhost:9000/studentprep-assets/diagrams/cell.png", saved.getAssets().get(0).get("url"));
        verify(redisTemplate).delete("exam:questions:all");
    }

    @Test
    void testUpdateContext_PreservesOmittedFieldAndRejectsEmptyContext() {
        UUID ctxId = UUID.randomUUID();
        QuestionContext existing = new QuestionContext();
        existing.setId(ctxId);
        existing.setPassage("Existing passage");
        existing.setAssets(List.of(Map.of("type", "image", "url", "http://localhost/img.png")));

        when(contextRepository.findById(ctxId)).thenReturn(Optional.of(existing));
        when(contextRepository.save(any(QuestionContext.class))).thenAnswer(inv -> inv.getArgument(0));

        QuestionContext preserved = questionService.updateContext(
                ctxId,
                new ContextUpdateRequest("Edited text only", null)
        );
        assertEquals("Edited text only", preserved.getPassage());
        assertEquals(1, preserved.getAssets().size());

        assertThrows(IllegalArgumentException.class, () ->
                questionService.updateContext(ctxId, new ContextUpdateRequest("   ", List.of())));
        assertThrows(NoSuchElementException.class, () ->
                questionService.updateContext(UUID.randomUUID(), new ContextUpdateRequest("Text", List.of())));
    }
}
