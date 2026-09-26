package com.studentprep.exam;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studentprep.exam.dto.ExamPayloadResponse;
import com.studentprep.questionbank.Question;
import com.studentprep.student.Student;
import com.studentprep.student.StudentInternalAPI;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class ExamPayloadGenerator {

    private final ExamCacheService examCacheService;
    private final ObjectMapper objectMapper;
    private final StudentInternalAPI studentInternalAPI;

    private static final int EXAM_DURATION_MINUTES = 120; // 2 hours

    public ExamPayloadGenerator(ExamCacheService examCacheService, ObjectMapper objectMapper, StudentInternalAPI studentInternalAPI) {
        this.examCacheService = examCacheService;
        this.objectMapper = objectMapper;
        this.studentInternalAPI = studentInternalAPI;
    }

    @Transactional(readOnly = true)
    public ExamPayloadResponse getActivePayload(UUID userId) {
        List<Question> cachedQuestions = examCacheService.getCachedQuestions();

        Student student = studentInternalAPI.findById(userId).orElse(null);
        List<String> enrolledSubjectIds = new ArrayList<>();
        Map<String, String> enrolledSubjectNames = new HashMap<>();
        if (student != null) {
            for (com.studentprep.questionbank.Subject subject : student.getSubjects()) {
                enrolledSubjectIds.add(subject.getId().toString());
                enrolledSubjectNames.put(subject.getId().toString(), subject.getName());
            }
        }

        List<Question> filteredQuestions = new ArrayList<>();
        for (Question q : cachedQuestions) {
            if (student == null || q.getSubjectId() == null) {
                filteredQuestions.add(q);
            } else if (enrolledSubjectIds.contains(q.getSubjectId().toString())) {
                filteredQuestions.add(q);
            }
        }

        return generateStrippedPayload(filteredQuestions, student, enrolledSubjectNames);
    }

    private ExamPayloadResponse generateStrippedPayload(List<Question> questions, Student student, Map<String, String> enrolledSubjectNames) {
        List<Object> strippedQuestions = new ArrayList<>();
        Map<String, String> contextsMap = new HashMap<>();
        
        Map<String, List<Object>> groupedQuestions = new HashMap<>();
        List<Object> standaloneQuestions = new ArrayList<>();

        long hashSeed = 0;
        for (Question q : questions) {
            hashSeed += q.getId().hashCode();
            Map<String, Object> map = this.objectMapper.convertValue(q, new TypeReference<Map<String, Object>>() {});
            
            String subjectId = q.getSubjectId() != null ? q.getSubjectId().toString() : null;
            if (subjectId != null) {
                Map<String, String> subjMap = new HashMap<>();
                subjMap.put("id", subjectId);
                String subjName = enrolledSubjectNames.get(subjectId);
                subjMap.put("name", subjName != null ? subjName : "Unknown Subject");
                map.put("subject", subjMap);
            }
            Map<String, Object> content = (Map<String, Object>) map.get("content");
            if (content != null) {
                content.remove("correctOption");
            }
            if (q.getContext() != null) {
                String ctxId = q.getContext().getId().toString();
                map.put("contextId", ctxId);
                contextsMap.put(ctxId, q.getContext().getPassage());
                groupedQuestions.computeIfAbsent(ctxId, k -> new ArrayList<>()).add(map);
            } else {
                standaloneQuestions.add(map);
            }
        }
        
        List<List<Object>> allGroups = new ArrayList<>();
        allGroups.addAll(groupedQuestions.values());
        for (Object sq : standaloneQuestions) {
            allGroups.add(Collections.singletonList(sq));
        }
        
        long seed = hashSeed == 0 ? 12345L : hashSeed;
        Collections.shuffle(allGroups, new Random(seed));
        
        for (List<Object> group : allGroups) {
            strippedQuestions.addAll(group);
        }
        
        ExamPayloadResponse response = new ExamPayloadResponse();
        response.setExamId(UUID.randomUUID());
        response.setShuffleSeed(seed);
        response.setDurationMinutes(EXAM_DURATION_MINUTES);
        response.setQuestions(strippedQuestions);
        response.setContexts(contextsMap);
        if (student != null) {
            response.setStudent(new com.studentprep.exam.dto.StudentProfileDto(student.getName(), student.getRegistrationNumber(), student.getExamCenter()));
        }
        return response;
    }
}
