package com.studentprep.analytics;

import com.studentprep.exam.ExamModerationUpdatedEvent;
import com.studentprep.exam.ExamSessionStatus;
import com.studentprep.exam.ExamSubmittedEvent;
import com.studentprep.student.Student;
import com.studentprep.student.StudentInternalAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class ExamGraderListener {

    private static final Logger log = LoggerFactory.getLogger(ExamGraderListener.class);

    private final StudentInternalAPI studentInternalAPI;
    private final ExamResultRepository examResultRepository;
    private final LeaderboardService leaderboardService;

    public ExamGraderListener(
            StudentInternalAPI studentInternalAPI,
            ExamResultRepository examResultRepository,
            LeaderboardService leaderboardService) {
        this.studentInternalAPI = studentInternalAPI;
        this.examResultRepository = examResultRepository;
        this.leaderboardService = leaderboardService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onExamSubmitted(ExamSubmittedEvent event) {
        if (event == null || event.sessionId() == null) {
            log.warn("Received null ExamSubmittedEvent or missing sessionId");
            return;
        }

        log.info("Grading exam session: {}, status: {}", event.sessionId(), event.terminalStatus());

        // Skip grading and leaderboard completely for malpractice tab switch termination
        if (event.terminalStatus() == ExamSessionStatus.FLAGGED_TAB_SWITCH) {
            log.info("Session {} flagged for tab switch (malpractice). Skipping grading and leaderboard.", event.sessionId());
            return;
        }

        // Enforce idempotency: check if session already graded
        if (examResultRepository.findByExamSessionId(event.sessionId()).isPresent()) {
            log.info("Exam session {} has already been graded. Idempotent skip.", event.sessionId());
            return;
        }

        UUID studentId = event.userId();
        Student student = studentId != null ? studentInternalAPI.findById(studentId).orElse(null) : null;
        if (student == null) {
            log.error("Student not found for id: {}", studentId);
            return;
        }

        // Null-safe answers extraction from finalState
        Map<String, String> answers = extractAnswers(event.finalState());

        // Grade strictly from frozen snapshotPayload
        int totalScore = 0;
        int maxScore = 0;
        Map<String, TopicStats> topicBreakdown = new HashMap<>();

        Map<String, Object> snapshot = event.snapshotPayload();
        Object questionsObj = snapshot != null ? snapshot.get("questions") : null;

        if (questionsObj instanceof List<?> questionList) {
            for (Object item : questionList) {
                if (!(item instanceof Map<?, ?> qMap)) {
                    continue;
                }

                maxScore++;

                String questionId = extractQuestionId(qMap);
                String topicKey = extractTopicKey(qMap);

                TopicStats stats = topicBreakdown.computeIfAbsent(topicKey, k -> new TopicStats(0, 0));
                stats.setTotal(stats.getTotal() + 1);

                String correctOption = extractCorrectOption(qMap);
                String studentAnswer = questionId != null ? answers.get(questionId) : null;

                if (correctOption != null && correctOption.equalsIgnoreCase(studentAnswer)) {
                    totalScore++;
                    stats.setCorrect(stats.getCorrect() + 1);
                }
            }
        }

        ExamResultModerationStatus moderationStatus = (event.terminalStatus() == ExamSessionStatus.LATE_SUBMISSION_FLAGGED)
                ? ExamResultModerationStatus.PENDING_REVIEW
                : ExamResultModerationStatus.ELIGIBLE;

        ExamResult result = new ExamResult();
        result.setStudent(student);
        result.setExamSessionId(event.sessionId());
        result.setTotalScore(totalScore);
        result.setMaxScore(maxScore);
        result.setTopicBreakdown(topicBreakdown);
        result.setModerationStatus(moderationStatus);

        try {
            examResultRepository.save(result);
            log.info("Saved ExamResult for session {}: score={}/{}, moderationStatus={}",
                    event.sessionId(), totalScore, maxScore, moderationStatus);
        } catch (DataIntegrityViolationException ex) {
            log.warn("Concurrent duplicate result save detected for session {}. Idempotent return.", event.sessionId());
            return;
        }

        // Only update leaderboard if eligible
        if (moderationStatus == ExamResultModerationStatus.ELIGIBLE) {
            leaderboardService.updateScore(studentId.toString(), totalScore);
        } else {
            log.info("Exam session {} marked as {}. Skipping leaderboard update until cleared.",
                    event.sessionId(), moderationStatus);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onExamModerationUpdated(ExamModerationUpdatedEvent event) {
        if (event == null) {
            return;
        }

        log.info("Handling ExamModerationUpdatedEvent: action={}, sessionId={}, studentId={}",
                event.action(), event.sessionId(), event.studentId());

        if ("CLEAR_LATE".equalsIgnoreCase(event.action())) {
            if (event.sessionId() != null) {
                examResultRepository.findByExamSessionId(event.sessionId()).ifPresent(result -> {
                    result.setModerationStatus(ExamResultModerationStatus.CLEARED);
                    result.setModeratedBy(event.actor());
                    result.setModeratedAt(event.occurredAt());
                    examResultRepository.save(result);
                    if (result.getStudent() != null) {
                        leaderboardService.updateScore(result.getStudent().getId().toString(), result.getTotalScore());
                    }
                    log.info("Promoted session {} to CLEARED and updated leaderboard", event.sessionId());
                });
            }
        } else if ("VOID".equalsIgnoreCase(event.action())) {
            if (event.sessionId() != null) {
                examResultRepository.findByExamSessionId(event.sessionId()).ifPresent(result -> {
                    result.setModerationStatus(ExamResultModerationStatus.VOIDED);
                    result.setModeratedBy(event.actor());
                    result.setModeratedAt(event.occurredAt());
                    examResultRepository.save(result);
                    if (result.getStudent() != null) {
                        leaderboardService.removeEntry(result.getStudent().getId().toString());
                    }
                    log.info("Voided result for session {} and removed from leaderboard", event.sessionId());
                });
            }
        } else if ("GRANT_RETAKE".equalsIgnoreCase(event.action())) {
            if (event.studentId() != null) {
                List<ExamResult> results = examResultRepository.findByStudentIdOrderByGradedAtDesc(event.studentId());
                for (ExamResult r : results) {
                    if (r.getModerationStatus() == ExamResultModerationStatus.PENDING_REVIEW) {
                        r.setModerationStatus(ExamResultModerationStatus.VOIDED);
                        r.setModeratedBy(event.actor());
                        r.setModeratedAt(event.occurredAt());
                        examResultRepository.save(r);
                    }
                }
                leaderboardService.removeEntry(event.studentId().toString());
                log.info("Handled GRANT_RETAKE for student {}", event.studentId());
            }
        }
    }

    private static Map<String, String> extractAnswers(Map<String, Object> finalState) {
        if (finalState == null) {
            return Collections.emptyMap();
        }
        Object ansObj = finalState.get("answers");
        if (!(ansObj instanceof Map<?, ?> ansMap)) {
            return Collections.emptyMap();
        }

        Map<String, String> answers = new HashMap<>();
        for (Map.Entry<?, ?> entry : ansMap.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                answers.put(entry.getKey().toString(), entry.getValue().toString());
            }
        }
        return answers;
    }

    private static String extractQuestionId(Map<?, ?> qMap) {
        Object idObj = qMap.get("id");
        return idObj != null ? idObj.toString() : null;
    }

    private static String extractTopicKey(Map<?, ?> qMap) {
        String subjectName = "General";
        Object subjectObj = qMap.get("subject");
        if (subjectObj instanceof Map<?, ?> sm) {
            Object sName = sm.get("name");
            if (sName != null && !sName.toString().isBlank()) {
                subjectName = sName.toString();
            }
        } else if (qMap.get("subjectName") != null) {
            subjectName = qMap.get("subjectName").toString();
        }

        Object topicObj = qMap.get("topic");
        String topicName = topicObj != null && !topicObj.toString().isBlank()
                ? topicObj.toString()
                : "Unknown";

        return subjectName + " - " + topicName;
    }

    private static String extractCorrectOption(Map<?, ?> qMap) {
        Object contentObj = qMap.get("content");
        if (contentObj instanceof Map<?, ?> cm) {
            Object opt = cm.get("correctOption");
            if (opt != null) {
                return opt.toString();
            }
        }
        Object directOpt = qMap.get("correctOption");
        return directOpt != null ? directOpt.toString() : null;
    }
}
