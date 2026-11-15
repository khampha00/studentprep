package com.studentprep.exam;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studentprep.exam.dto.ExamPayloadResponse;
import com.studentprep.questionbank.Question;
import com.studentprep.questionbank.QuestionContext;
import com.studentprep.questionbank.Subject;
import com.studentprep.student.Student;
import com.studentprep.student.StudentInternalAPI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class ExamPayloadGeneratorTest {

    private ExamCacheService examCacheService;
    private StudentInternalAPI studentInternalAPI;
    private ExamTimingProperties timingProperties;
    private ObjectMapper objectMapper;
    private ExamPayloadGenerator generator;

    @BeforeEach
    void setUp() {
        examCacheService = Mockito.mock(ExamCacheService.class);
        studentInternalAPI = Mockito.mock(StudentInternalAPI.class);
        timingProperties = new ExamTimingProperties(120, 30, 900);
        objectMapper = new ObjectMapper();
        generator = new ExamPayloadGenerator(examCacheService, objectMapper, studentInternalAPI, timingProperties);
    }

    @Test
    void testDeterministicShuffleSeedOrdering() {
        List<Question> questions = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Question q = new Question();
            q.setId(UUID.randomUUID());
            q.setTopic("Topic " + i);
            Map<String, Object> content = new HashMap<>();
            content.put("text", "Question " + i);
            content.put("correctOption", "A");
            q.setContent(content);
            questions.add(q);
        }

        when(examCacheService.getCachedQuestions()).thenReturn(questions);
        when(studentInternalAPI.findById(any())).thenReturn(Optional.empty());

        Map<String, Object> snapshotA1 = generator.buildSessionSnapshot(UUID.randomUUID(), 4321);
        Map<String, Object> snapshotA2 = generator.buildSessionSnapshot(UUID.randomUUID(), 4321);
        Map<String, Object> snapshotB = generator.buildSessionSnapshot(UUID.randomUUID(), 8765);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> qListA1 = (List<Map<String, Object>>) snapshotA1.get("questions");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> qListA2 = (List<Map<String, Object>>) snapshotA2.get("questions");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> qListB = (List<Map<String, Object>>) snapshotB.get("questions");

        List<String> idsA1 = qListA1.stream().map(m -> m.get("id").toString()).toList();
        List<String> idsA2 = qListA2.stream().map(m -> m.get("id").toString()).toList();
        List<String> idsB = qListB.stream().map(m -> m.get("id").toString()).toList();

        assertThat(idsA1).isEqualTo(idsA2);
        assertThat(idsA1).isNotEqualTo(idsB);
    }

    @Test
    void testPassageGroupingPreservation() {
        QuestionContext ctx1 = new QuestionContext();
        ctx1.setId(UUID.randomUUID());
        ctx1.setPassage("Passage 1 text");

        QuestionContext ctx2 = new QuestionContext();
        ctx2.setId(UUID.randomUUID());
        ctx2.setPassage("Passage 2 text");

        List<Question> questions = new ArrayList<>();

        for (int i = 0; i < 3; i++) {
            Question q = new Question();
            q.setId(UUID.randomUUID());
            q.setContext(ctx1);
            Map<String, Object> content = new HashMap<>();
            content.put("text", "Passage 1 Q" + i);
            content.put("correctOption", "B");
            q.setContent(content);
            questions.add(q);
        }

        for (int i = 0; i < 2; i++) {
            Question q = new Question();
            q.setId(UUID.randomUUID());
            q.setContext(ctx2);
            Map<String, Object> content = new HashMap<>();
            content.put("text", "Passage 2 Q" + i);
            content.put("correctOption", "C");
            q.setContent(content);
            questions.add(q);
        }

        for (int i = 0; i < 5; i++) {
            Question q = new Question();
            q.setId(UUID.randomUUID());
            Map<String, Object> content = new HashMap<>();
            content.put("text", "Standalone Q" + i);
            content.put("correctOption", "D");
            q.setContent(content);
            questions.add(q);
        }

        when(examCacheService.getCachedQuestions()).thenReturn(questions);
        when(studentInternalAPI.findById(any())).thenReturn(Optional.empty());

        int[] seeds = {1111, 2222, 3333, 4444, 9999, 7777};
        for (int seed : seeds) {
            Map<String, Object> snapshot = generator.buildSessionSnapshot(UUID.randomUUID(), seed);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> qList = (List<Map<String, Object>>) snapshot.get("questions");

            List<Integer> ctx1Indices = new ArrayList<>();
            List<Integer> ctx2Indices = new ArrayList<>();
            for (int idx = 0; idx < qList.size(); idx++) {
                String cId = (String) qList.get(idx).get("contextId");
                if (ctx1.getId().toString().equals(cId)) {
                    ctx1Indices.add(idx);
                } else if (ctx2.getId().toString().equals(cId)) {
                    ctx2Indices.add(idx);
                }
            }

            assertThat(ctx1Indices).hasSize(3);
            assertThat(ctx1Indices.get(1)).isEqualTo(ctx1Indices.get(0) + 1);
            assertThat(ctx1Indices.get(2)).isEqualTo(ctx1Indices.get(1) + 1);

            assertThat(ctx2Indices).hasSize(2);
            assertThat(ctx2Indices.get(1)).isEqualTo(ctx2Indices.get(0) + 1);
        }
    }

    @Test
    void testRecursiveCorrectOptionStripping() {
        Question q1 = new Question();
        q1.setId(UUID.randomUUID());
        Map<String, Object> content1 = new HashMap<>();
        content1.put("text", "Standard question");
        content1.put("correctOption", "A");
        content1.put("options", List.of(
                Map.of("id", "A", "text", "Alpha", "isCorrect", true),
                Map.of("id", "B", "text", "Beta", "isCorrect", false)
        ));
        q1.setContent(content1);

        Question q2 = new Question();
        q2.setId(UUID.randomUUID());
        Map<String, Object> content2 = new HashMap<>();
        content2.put("passage", "Passage text");
        List<Map<String, Object>> childQuestions = new ArrayList<>();

        Map<String, Object> child1 = new HashMap<>();
        child1.put("id", "child-1");
        child1.put("text", "Child question 1");
        child1.put("correctOption", "B");
        child1.put("options", List.of("One", "Two", "Three"));
        childQuestions.add(child1);

        Map<String, Object> child2 = new HashMap<>();
        child2.put("id", "child-2");
        child2.put("text", "Child question 2");
        child2.put("correctOption", "C");
        childQuestions.add(child2);

        content2.put("childQuestions", childQuestions);
        q2.setContent(content2);

        when(examCacheService.getCachedQuestions()).thenReturn(List.of(q1, q2));
        when(studentInternalAPI.findById(any())).thenReturn(Optional.empty());

        Map<String, Object> rawSnapshot = generator.buildSessionSnapshot(UUID.randomUUID(), 5555);

        String rawJson = objectMapper.valueToTree(rawSnapshot).toString();
        assertThat(rawJson).contains("correctOption");
        assertThat(rawJson).contains("isCorrect");

        ExamPayloadResponse response = generator.projectFromSnapshot(rawSnapshot);

        String strippedJson = objectMapper.valueToTree(response).toString();
        assertThat(strippedJson).doesNotContain("correctOption");
        assertThat(strippedJson).doesNotContain("isCorrect");

        String rawJsonAfterProject = objectMapper.valueToTree(rawSnapshot).toString();
        assertThat(rawJsonAfterProject).contains("correctOption");
        assertThat(rawJsonAfterProject).contains("isCorrect");
    }

    @Test
    void testContextsMappingPreserved() {
        QuestionContext ctx = new QuestionContext();
        ctx.setId(UUID.randomUUID());
        ctx.setPassage("Context passage for reading comprehension");

        Question q = new Question();
        q.setId(UUID.randomUUID());
        q.setContext(ctx);
        q.setContent(Map.of("text", "Reading question", "correctOption", "A"));

        when(examCacheService.getCachedQuestions()).thenReturn(List.of(q));
        when(studentInternalAPI.findById(any())).thenReturn(Optional.empty());

        Map<String, Object> snapshot = generator.buildSessionSnapshot(UUID.randomUUID(), 1234);
        ExamPayloadResponse response = generator.projectFromSnapshot(snapshot);

        assertThat(response.getContexts()).containsKey(ctx.getId().toString());
        assertThat(response.getContexts().get(ctx.getId().toString())).isEqualTo("Context passage for reading comprehension");
    }

    @Test
    void testContextAssetsProjectedAndLegacySnapshotsHandledGracefully() {
        QuestionContext ctx = new QuestionContext();
        UUID ctxId = UUID.randomUUID();
        ctx.setId(ctxId);
        ctx.setPassage("Use the diagram below to answer questions 1 and 2");
        ctx.setAssets(List.of(Map.of(
                "type", "image",
                "url", "http://localhost:9000/studentprep-assets/diagrams/yam.png"
        )));

        Question q = new Question();
        q.setId(UUID.randomUUID());
        q.setContext(ctx);
        q.setContent(Map.of("text", "Yam is used in this set-up because it", "correctOption", "A"));

        when(examCacheService.getCachedQuestions()).thenReturn(List.of(q));
        when(studentInternalAPI.findById(any())).thenReturn(Optional.empty());

        Map<String, Object> snapshot = generator.buildSessionSnapshot(UUID.randomUUID(), 1234);
        assertThat(snapshot).containsKey("contextAssets");

        ExamPayloadResponse response = generator.projectFromSnapshot(snapshot);
        assertThat(response.getContextAssets()).containsKey(ctxId.toString());
        assertThat(response.getContextAssets().get(ctxId.toString()))
                .hasSize(1)
                .first()
                .extracting(asset -> asset.get("url"))
                .isEqualTo("http://localhost:9000/studentprep-assets/diagrams/yam.png");

        // Backward compatibility: historical snapshots without "contextAssets" must project an empty map
        Map<String, Object> legacySnapshot = new HashMap<>(snapshot);
        legacySnapshot.remove("contextAssets");
        ExamPayloadResponse legacyResponse = generator.projectFromSnapshot(legacySnapshot);
        assertThat(legacyResponse.getContextAssets()).isNotNull().isEmpty();
    }
}
