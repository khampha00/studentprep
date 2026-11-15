package com.studentprep.ingestion;

import com.studentprep.questionbank.Question;
import com.studentprep.questionbank.QuestionContext;
import com.studentprep.questionbank.QuestionContextRepository;
import com.studentprep.questionbank.QuestionRepository;
import com.studentprep.questionbank.Subject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostProcessTest {

    @Test
    void postProcessContextGroups_JobScoped_RespectsFollowUpAndTerminatesOnFalse() {
        QuestionRepository questionRepository = mock(QuestionRepository.class);
        QuestionContextRepository contextRepository = mock(QuestionContextRepository.class);
        AsyncIngestionWorker worker = new AsyncIngestionWorker(
                null,
                null,
                questionRepository,
                null,
                contextRepository,
                null
        );

        UUID jobId = UUID.randomUUID();
        Subject subject = new Subject("English");
        subject.setId(UUID.randomUUID());

        Question q1 = questionWithContent(subject, jobId, Map.of(
                "shared_context", "Comprehension passage about climate.",
                "text", "According to the passage, what changed?",
                "is_follow_up", false
        ));
        Question q2 = questionWithContent(subject, jobId, Map.of(
                "shared_context", "",
                "text", "Which statement best summarizes paragraph two?",
                "is_follow_up", true
        ));
        Question q3 = questionWithContent(subject, jobId, Map.of(
                "shared_context", "",
                "text", "What is the synonym of ephemeral?",
                "is_follow_up", false
        ));
        Question q4 = questionWithContent(subject, jobId, Map.of(
                "shared_context", "",
                "text", "Sub-question with stale follow-up after reset",
                "is_follow_up", true
        ));

        when(questionRepository.findByIngestionJobIdOrderByCreatedAtAsc(jobId))
                .thenReturn(List.of(q1, q2, q3, q4));
        when(contextRepository.save(any(QuestionContext.class))).thenAnswer(invocation -> {
            QuestionContext ctx = invocation.getArgument(0);
            ctx.setId(UUID.randomUUID());
            return ctx;
        });

        worker.postProcessContextGroups(jobId);

        assertNotNull(q1.getContext(), "q1 introduces shared_context and must own a context");
        assertSame(q1.getContext(), q2.getContext(), "q2 with is_follow_up=true must share q1's context");
        assertNull(q3.getContext(), "q3 with is_follow_up=false must remain standalone");
        assertNull(q4.getContext(), "q3 reset active context so q4 must not inherit q1's passage");

        verify(questionRepository).findByIngestionJobIdOrderByCreatedAtAsc(jobId);
        verify(questionRepository, never()).findBySubjectOrderByCreatedAtAsc(any());
    }

    @Test
    void postProcessContextGroups_NullFollowUp_LinksOnlyUnambiguousGapFillContinuation() {
        QuestionRepository questionRepository = mock(QuestionRepository.class);
        QuestionContextRepository contextRepository = mock(QuestionContextRepository.class);
        AsyncIngestionWorker worker = new AsyncIngestionWorker(
                null,
                null,
                questionRepository,
                null,
                contextRepository,
                null
        );

        UUID jobId = UUID.randomUUID();
        Subject subject = new Subject("English");
        subject.setId(UUID.randomUUID());

        Question q1 = questionWithContent(subject, jobId, Map.of(
                "shared_context", "The economy ---1--- rapidly last year.",
                "text", "Fill in gap 1"
        ));
        Question q2 = questionWithContent(subject, jobId, Map.of(
                "text", "Fill in gap 2"
        ));
        Question q3 = questionWithContent(subject, jobId, Map.of(
                "text", "Choose the word opposite in meaning to candid."
        ));
        Question q4 = questionWithContent(subject, jobId, Map.of(
                "shared_context", "Another cloze passage ---3---.",
                "text", "Fill in gap 3"
        ));
        Question q5 = questionWithContent(subject, jobId, Map.of(
                "text", "Fill in gap 4",
                "is_follow_up", false
        ));

        when(questionRepository.findByIngestionJobIdOrderByCreatedAtAsc(jobId))
                .thenReturn(List.of(q1, q2, q3, q4, q5));
        when(contextRepository.save(any(QuestionContext.class))).thenAnswer(invocation -> {
            QuestionContext ctx = invocation.getArgument(0);
            ctx.setId(UUID.randomUUID());
            return ctx;
        });

        worker.postProcessContextGroups(jobId);

        assertNotNull(q1.getContext());
        assertSame(q1.getContext(), q2.getContext(), "Unambiguous gap-fill continuation with null flag links");
        assertNull(q3.getContext(), "Standard question with null flag resets active context");
        assertNotNull(q4.getContext());
        assertEquals("Another cloze passage ---3---.", q4.getContext().getPassage());
        assertNull(q5.getContext(), "Explicit is_follow_up=false overrides even gap-fill text");
    }

    @Test
    void postProcessContextGroups_SubjectFallback_DoesNotLinkWhenIsFollowUpIsFalse() {
        QuestionRepository questionRepository = mock(QuestionRepository.class);
        QuestionContextRepository contextRepository = mock(QuestionContextRepository.class);
        AsyncIngestionWorker worker = new AsyncIngestionWorker(
                null,
                null,
                questionRepository,
                null,
                contextRepository,
                null
        );

        Subject subject = new Subject("Economics");
        subject.setId(UUID.randomUUID());

        Question q1 = questionWithContent(subject, null, Map.of(
                "shared_context", "Passage text",
                "text", "Q1"
        ));
        Question q2 = questionWithContent(subject, null, Map.of(
                "is_follow_up", false,
                "text", "Standalone Q2"
        ));

        when(questionRepository.findBySubjectOrderByCreatedAtAsc(subject)).thenReturn(List.of(q1, q2));
        when(contextRepository.save(any(QuestionContext.class))).thenAnswer(invocation -> {
            QuestionContext ctx = invocation.getArgument(0);
            ctx.setId(UUID.randomUUID());
            return ctx;
        });

        worker.postProcessContextGroups(subject);

        assertNotNull(q1.getContext(), "q1 should have a context");
        assertNull(q2.getContext(), "q2 with is_follow_up=false must not inherit q1's context");
        verify(questionRepository).findBySubjectOrderByCreatedAtAsc(subject);
    }

    private static Question questionWithContent(Subject subject, UUID jobId, Map<String, Object> content) {
        Question question = new Question();
        question.setId(UUID.randomUUID());
        question.setSubject(subject);
        question.setIngestionJobId(jobId);
        question.setContent(new HashMap<>(content));
        return question;
    }
}
