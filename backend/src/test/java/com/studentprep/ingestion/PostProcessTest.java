package com.studentprep.ingestion;

import com.studentprep.questionbank.*;
import org.junit.jupiter.api.Test;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class PostProcessTest {
    @Test
    public void testPostProcess() {
        QuestionRepository qRepo = mock(QuestionRepository.class);
        QuestionContextRepository cRepo = mock(QuestionContextRepository.class);
        
        AsyncIngestionWorker worker = new AsyncIngestionWorker(null, null, qRepo, null, cRepo, null);
        
        Subject subject = new Subject();
        
        Question q1 = new Question();
        Map<String, Object> content1 = new HashMap<>();
        content1.put("shared_context", "Passage text");
        q1.setContent(content1);
        
        Question q2 = new Question();
        Map<String, Object> content2 = new HashMap<>();
        // LLM failed to set is_follow_up
        content2.put("is_follow_up", false);
        q2.setContent(content2);
        
        when(qRepo.findBySubjectOrderByCreatedAtAsc(subject)).thenReturn(Arrays.asList(q1, q2));
        when(cRepo.save(any(QuestionContext.class))).thenAnswer(i -> {
            QuestionContext ctx = i.getArgument(0);
            ctx.setId(UUID.randomUUID());
            return ctx;
        });
        
        worker.postProcessContextGroups(subject);
        
        assertNotNull(q1.getContext(), "q1 should have a context");
        assertNotNull(q2.getContext(), "q2 should have a context because it has no shared_context of its own!");
    }
}
