package com.studentprep.exam;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studentprep.exam.dto.ExamPayloadResponse;
import com.studentprep.exam.dto.StudentProfileDto;
import com.studentprep.questionbank.Question;
import com.studentprep.questionbank.Subject;
import com.studentprep.student.Student;
import com.studentprep.student.StudentInternalAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

@Service
public class ExamPayloadGenerator {

    private static final Logger log = LoggerFactory.getLogger(ExamPayloadGenerator.class);

    private final ExamCacheService examCacheService;
    private final ObjectMapper objectMapper;
    private final StudentInternalAPI studentInternalAPI;
    private final ExamTimingProperties timingProperties;

    public ExamPayloadGenerator(
            ExamCacheService examCacheService,
            ObjectMapper objectMapper,
            StudentInternalAPI studentInternalAPI,
            ExamTimingProperties timingProperties
    ) {
        this.examCacheService = examCacheService;
        this.objectMapper = objectMapper;
        this.studentInternalAPI = studentInternalAPI;
        this.timingProperties = timingProperties;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> buildSessionSnapshot(UUID userId, Integer shuffleSeed) {
        List<Question> cachedQuestions = examCacheService.getCachedQuestions();

        Student student = (userId != null) ? studentInternalAPI.findById(userId).orElse(null) : null;
        List<String> enrolledSubjectIds = new ArrayList<>();
        Map<String, String> enrolledSubjectNames = new HashMap<>();
        if (student != null && student.getSubjects() != null) {
            for (Subject subject : student.getSubjects()) {
                if (subject != null && subject.getId() != null) {
                    enrolledSubjectIds.add(subject.getId().toString());
                    enrolledSubjectNames.put(subject.getId().toString(), subject.getName());
                }
            }
        }

        List<Question> filteredQuestions = new ArrayList<>();
        for (Question q : cachedQuestions) {
            if (q == null) {
                continue;
            }
            if (student == null || enrolledSubjectIds.isEmpty() || q.getSubjectId() == null) {
                filteredQuestions.add(q);
            } else if (enrolledSubjectIds.contains(q.getSubjectId().toString())) {
                filteredQuestions.add(q);
            }
        }

        Map<String, String> contextsMap = new HashMap<>();
        Map<String, List<Map<String, Object>>> contextAssetsMap = new HashMap<>();
        Map<String, List<Map<String, Object>>> groupedQuestions = new LinkedHashMap<>();
        List<Map<String, Object>> standaloneQuestions = new ArrayList<>();

        for (Question q : filteredQuestions) {
            Map<String, Object> map = objectMapper.convertValue(q, new TypeReference<Map<String, Object>>() {});
            if (q.getId() != null) {
                map.put("id", q.getId().toString());
            }

            String subjectId = q.getSubjectId() != null ? q.getSubjectId().toString() : null;
            if (subjectId != null) {
                Map<String, String> subjMap = new HashMap<>();
                subjMap.put("id", subjectId);
                String subjName = enrolledSubjectNames.get(subjectId);
                if (subjName == null && q.getSubject() != null) {
                    subjName = q.getSubject().getName();
                }
                subjMap.put("name", subjName != null ? subjName : "Unknown Subject");
                map.put("subject", subjMap);
            }

            if (q.getContext() != null && q.getContext().getId() != null) {
                String ctxId = q.getContext().getId().toString();
                map.put("contextId", ctxId);
                if (q.getContext().getPassage() != null) {
                    contextsMap.put(ctxId, q.getContext().getPassage());
                }
                List<Map<String, Object>> rawCtxAssets = q.getContext().getAssets();
                if (rawCtxAssets != null && !rawCtxAssets.isEmpty()) {
                    contextAssetsMap.put(ctxId, copyAssetList(rawCtxAssets));
                }
                groupedQuestions.computeIfAbsent(ctxId, k -> new ArrayList<>()).add(map);
            } else {
                standaloneQuestions.add(map);
            }
        }

        List<List<Map<String, Object>>> allGroups = new ArrayList<>();
        allGroups.addAll(groupedQuestions.values());
        for (Map<String, Object> sq : standaloneQuestions) {
            List<Map<String, Object>> single = new ArrayList<>();
            single.add(sq);
            allGroups.add(single);
        }

        // Sort groups by the first question's id to ensure a stable baseline order before shuffling
        allGroups.sort(Comparator.comparing(group -> {
            if (group.isEmpty() || group.get(0) == null) {
                return "";
            }
            Object id = group.get(0).get("id");
            return id != null ? id.toString() : "";
        }));

        long seed = (shuffleSeed != null) ? shuffleSeed.longValue() : 12345L;
        Collections.shuffle(allGroups, new Random(seed));

        List<Map<String, Object>> rawQuestions = new ArrayList<>();
        for (List<Map<String, Object>> group : allGroups) {
            rawQuestions.addAll(group);
        }

        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("examId", UUID.randomUUID().toString());
        snapshot.put("shuffleSeed", shuffleSeed != null ? shuffleSeed : 12345);
        snapshot.put("durationMinutes", (int) timingProperties.getDurationMinutes());
        snapshot.put("questions", rawQuestions);
        snapshot.put("contexts", contextsMap);
        snapshot.put("contextAssets", contextAssetsMap);
        if (student != null) {
            Map<String, String> studentMap = new HashMap<>();
            studentMap.put("name", student.getName());
            studentMap.put("registrationNumber", student.getRegistrationNumber());
            snapshot.put("student", studentMap);
        }

        return snapshot;
    }

    public ExamPayloadResponse projectFromSnapshot(Map<String, Object> snapshotPayload) {
        if (snapshotPayload == null) {
            return new ExamPayloadResponse();
        }

        Object rawQuestionsObj = snapshotPayload.get("questions");
        List<Object> strippedQuestions = new ArrayList<>();
        if (rawQuestionsObj != null) {
            List<Object> clonedQuestions = objectMapper.convertValue(rawQuestionsObj, new TypeReference<List<Object>>() {});
            stripAnswerKeysRecursively(clonedQuestions);
            strippedQuestions = clonedQuestions;
        }

        @SuppressWarnings("unchecked")
        Map<String, String> contexts = (Map<String, String>) snapshotPayload.get("contexts");
        if (contexts == null) {
            contexts = Collections.emptyMap();
        }

        Map<String, List<Map<String, Object>>> contextAssets = extractContextAssets(snapshotPayload.get("contextAssets"));

        int durationMinutes = (int) timingProperties.getDurationMinutes();
        Object durObj = snapshotPayload.get("durationMinutes");
        if (durObj instanceof Number num) {
            durationMinutes = num.intValue();
        }

        long shuffleSeed = 12345L;
        Object seedObj = snapshotPayload.get("shuffleSeed");
        if (seedObj instanceof Number num) {
            shuffleSeed = num.longValue();
        }

        UUID examId = UUID.randomUUID();
        Object examIdObj = snapshotPayload.get("examId");
        if (examIdObj instanceof String str) {
            try {
                examId = UUID.fromString(str);
            } catch (Exception ex) {
                log.debug("Could not parse examId string: {}", str);
            }
        } else if (examIdObj instanceof UUID uuid) {
            examId = uuid;
        }

        StudentProfileDto studentProfile = null;
        Object studentObj = snapshotPayload.get("student");
        if (studentObj instanceof Map<?, ?> smap) {
            String name = (String) smap.get("name");
            String regNo = (String) smap.get("registrationNumber");
            studentProfile = new StudentProfileDto(name, regNo);
        }

        ExamPayloadResponse response = new ExamPayloadResponse();
        response.setExamId(examId);
        response.setShuffleSeed(shuffleSeed);
        response.setDurationMinutes(durationMinutes);
        response.setQuestions(strippedQuestions);
        response.setContexts(contexts);
        response.setContextAssets(contextAssets);
        if (studentProfile != null) {
            response.setStudent(studentProfile);
        }

        return response;
    }

    private static List<Map<String, Object>> copyAssetList(List<Map<String, Object>> rawAssets) {
        List<Map<String, Object>> copied = new ArrayList<>();
        if (rawAssets == null) {
            return copied;
        }
        for (Map<String, Object> asset : rawAssets) {
            if (asset != null && !asset.isEmpty()) {
                copied.add(new LinkedHashMap<>(asset));
            }
        }
        return copied;
    }

    private Map<String, List<Map<String, Object>>> extractContextAssets(Object rawAssetsObj) {
        if (rawAssetsObj == null) {
            return Collections.emptyMap();
        }
        Map<String, List<Map<String, Object>>> cloned = objectMapper.convertValue(
                rawAssetsObj,
                new TypeReference<Map<String, List<Map<String, Object>>>>() {}
        );
        return cloned != null ? cloned : Collections.emptyMap();
    }

    @Transactional(readOnly = true)
    public ExamPayloadResponse getActivePayload(UUID userId) {
        int defaultSeed = 12345;
        Map<String, Object> snapshot = buildSessionSnapshot(userId, defaultSeed);
        return projectFromSnapshot(snapshot);
    }

    @SuppressWarnings("unchecked")
    private void stripAnswerKeysRecursively(Object node) {
        if (node instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = (Map<String, Object>) rawMap;
            map.remove("correctOption");
            map.remove("isCorrect");
            for (Object value : new ArrayList<>(map.values())) {
                stripAnswerKeysRecursively(value);
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                stripAnswerKeysRecursively(item);
            }
        }
    }
}
