package com.studentprep.exam;

import com.studentprep.exam.dto.ExamPayloadResponse;
import com.studentprep.exam.dto.ExamStartResponse;
import com.studentprep.exam.dto.ExamSyncRequest;
import com.studentprep.questionbank.Question;
import com.studentprep.questionbank.QuestionRepository;
import com.studentprep.questionbank.QuestionStatus;
import com.studentprep.student.Student;
import com.studentprep.student.StudentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.util.AopTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class ExamSnapshotAndGradingIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ExamSnapshotAndGradingIntegrationTest.class);

    private final ExamSessionManager sessionManager;
    private final ExamSessionRepository sessionRepository;
    private final StudentRepository studentRepository;
    private final QuestionRepository questionRepository;
    private final PasswordEncoder passwordEncoder;
    private final ExamExpiryScheduler expiryScheduler;

    private final List<UUID> createdStudentIds = new ArrayList<>();
    private final List<UUID> createdQuestionIds = new ArrayList<>();

    public ExamSnapshotAndGradingIntegrationTest(
            ExamSessionManager sessionManager,
            ExamSessionRepository sessionRepository,
            StudentRepository studentRepository,
            QuestionRepository questionRepository,
            PasswordEncoder passwordEncoder,
            ExamExpiryScheduler expiryScheduler
    ) {
        this.sessionManager = sessionManager;
        this.sessionRepository = sessionRepository;
        this.studentRepository = studentRepository;
        this.questionRepository = questionRepository;
        this.passwordEncoder = passwordEncoder;
        this.expiryScheduler = expiryScheduler;
    }

    @AfterEach
    void tearDown() {
        for (UUID studentId : createdStudentIds) {
            try {
                List<ExamSession> sessions = sessionRepository.findAllByUserIdAndSupersededFalse(studentId);
                sessionRepository.deleteAll(sessions);
                studentRepository.deleteById(studentId);
            } catch (Exception ex) {
                log.warn("Error cleaning up student {}: {}", studentId, ex.getMessage());
            }
        }
        for (UUID questionId : createdQuestionIds) {
            try {
                questionRepository.deleteById(questionId);
            } catch (Exception ex) {
                log.warn("Error cleaning up question {}: {}", questionId, ex.getMessage());
            }
        }
    }

    private Student createStudent(String suffix) {
        Student student = new Student();
        student.setName("Integration Student " + suffix);
        student.setRegistrationNumber("REG_" + suffix + "_" + UUID.randomUUID().toString().substring(0, 8));
        student.setPinHash(passwordEncoder.encode("1234"));
        student.setPinResetRequired(false);
        student = studentRepository.save(student);
        createdStudentIds.add(student.getId());
        return student;
    }

    @Test
    void testSnapshotFreezeWhenQuestionEditedMidExam() {
        Student student = createStudent("freeze");

        ExamStartResponse startResp = sessionManager.startExam(student.getId());
        assertThat(startResp).isNotNull();
        assertThat(startResp.sessionId()).isNotNull();

        ExamSession session = sessionRepository.findById(startResp.sessionId()).orElseThrow();
        Map<String, Object> snapshot = session.getSnapshotPayload();
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.get("questions")).isNotNull();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> snapshotQuestions = (List<Map<String, Object>>) snapshot.get("questions");
        assertThat(snapshotQuestions).isNotEmpty();

        String targetQuestionId = snapshotQuestions.get(0).get("id").toString();
        UUID qUuid = UUID.fromString(targetQuestionId);

        Question dbQuestion = questionRepository.findById(qUuid).orElse(null);
        if (dbQuestion != null) {
            Map<String, Object> content = dbQuestion.getContent() != null ? new HashMap<>(dbQuestion.getContent()) : new HashMap<>();
            content.put("text", "MUTATED_QUESTION_TEXT_MID_EXAM");
            dbQuestion.setContent(content);
            questionRepository.save(dbQuestion);
        }

        ExamPayloadResponse activePayload = sessionManager.getActivePayload(student.getId());
        assertThat(activePayload).isNotNull();
        assertThat(activePayload.getQuestions()).isNotEmpty();

        for (Object qObj : activePayload.getQuestions()) {
            if (qObj instanceof Map<?, ?> qMap && targetQuestionId.equals(qMap.get("id"))) {
                Object cObj = qMap.get("content");
                if (cObj instanceof Map<?, ?> cMap) {
                    assertThat(cMap.get("text")).isNotEqualTo("MUTATED_QUESTION_TEXT_MID_EXAM");
                }
            }
        }
    }

    @Test
    void testReadOnlyPollSafetyOnExpiredSession() {
        Student student = createStudent("poll_safety");

        ExamStartResponse startResp = sessionManager.startExam(student.getId());
        ExamSession session = sessionRepository.findById(startResp.sessionId()).orElseThrow();

        session.setStartTime(Instant.now().minus(180, ChronoUnit.MINUTES));
        session.setExpectedEndTime(Instant.now().minus(60, ChronoUnit.MINUTES));
        sessionRepository.save(session);

        Map<String, Object> activeSession = sessionManager.getActiveSession(student.getId());
        assertThat(activeSession).isNull();

        ExamSession afterPoll = sessionRepository.findById(session.getId()).orElseThrow();
        assertThat(afterPoll.getStatus()).isEqualTo(ExamSessionStatus.IN_PROGRESS);
    }

    @Test
    void testConcurrentDoubleSubmitIdempotency() {
        Student student = createStudent("double_submit");

        ExamStartResponse startResp = sessionManager.startExam(student.getId());
        UUID sessionId = startResp.sessionId();

        ExamSessionStatus firstStatus = sessionManager.submitExam(sessionId, student.getId());
        assertThat(firstStatus).isEqualTo(ExamSessionStatus.SUBMITTED);

        ExamSessionStatus secondStatus = sessionManager.submitExam(sessionId, student.getId());
        assertThat(secondStatus).isEqualTo(ExamSessionStatus.SUBMITTED);

        ExamSession session = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(session.getStatus()).isEqualTo(ExamSessionStatus.SUBMITTED);
        assertThat(session.getEndTime()).isNotNull();
    }

    @Test
    void testSyncDeadlineEnforcementAnd30sGrace() {
        Student student = createStudent("sync_deadline");

        ExamStartResponse startResp = sessionManager.startExam(student.getId());
        UUID sessionId = startResp.sessionId();

        Map<String, Object> validState = new HashMap<>();
        validState.put("answers", Map.of("q1", "A"));
        validState.put("tabSwitchCount", 1);
        sessionManager.syncExam(sessionId, student.getId(), new ExamSyncRequest(validState));

        ExamSession session = sessionRepository.findById(sessionId).orElseThrow();
        session.setExpectedEndTime(Instant.now().minus(45, ChronoUnit.SECONDS));
        sessionRepository.save(session);

        Map<String, Object> overdueState = new HashMap<>();
        overdueState.put("answers", Map.of("q1", "B"));
        overdueState.put("tabSwitchCount", 1);

        assertThatThrownBy(() -> sessionManager.syncExam(sessionId, student.getId(), new ExamSyncRequest(overdueState)))
                .isInstanceOf(ExamDeadlineExpiredException.class)
                .hasFieldOrPropertyWithValue("code", "EXAM_WINDOW_EXPIRED");

        ExamSession expiredSession = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(expiredSession.getStatus()).isEqualTo(ExamSessionStatus.TIME_EXPIRED);
    }

    @Test
    void testOfflineSubmitGraceAndCutoff() {
        Student studentLate = createStudent("late_submit");
        ExamStartResponse startLate = sessionManager.startExam(studentLate.getId());
        ExamSession sessionLate = sessionRepository.findById(startLate.sessionId()).orElseThrow();

        sessionLate.setExpectedEndTime(Instant.now().minus(300, ChronoUnit.SECONDS));
        sessionRepository.save(sessionLate);

        ExamSessionStatus lateStatus = sessionManager.submitExam(sessionLate.getId(), studentLate.getId());
        assertThat(lateStatus).isEqualTo(ExamSessionStatus.LATE_SUBMISSION_FLAGGED);

        ExamSession refreshedLate = sessionRepository.findById(sessionLate.getId()).orElseThrow();
        assertThat(refreshedLate.getStatus()).isEqualTo(ExamSessionStatus.LATE_SUBMISSION_FLAGGED);

        Student studentCutoff = createStudent("cutoff_exceeded");
        ExamStartResponse startCutoff = sessionManager.startExam(studentCutoff.getId());
        ExamSession sessionCutoff = sessionRepository.findById(startCutoff.sessionId()).orElseThrow();

        sessionCutoff.setExpectedEndTime(Instant.now().minus(960, ChronoUnit.SECONDS));
        sessionRepository.save(sessionCutoff);

        assertThatThrownBy(() -> sessionManager.submitExam(sessionCutoff.getId(), studentCutoff.getId()))
                .isInstanceOf(ExamDeadlineExpiredException.class)
                .hasFieldOrPropertyWithValue("code", "OFFLINE_CUTOFF_EXCEEDED");

        ExamSession refreshedCutoff = sessionRepository.findById(sessionCutoff.getId()).orElseThrow();
        assertThat(refreshedCutoff.getStatus()).isEqualTo(ExamSessionStatus.TIME_EXPIRED);
    }

    @Test
    void testSingleSitting409Enforcement() {
        Student studentSubmitted = createStudent("sitting_submitted");
        ExamStartResponse startSubmitted = sessionManager.startExam(studentSubmitted.getId());
        sessionManager.submitExam(startSubmitted.sessionId(), studentSubmitted.getId());

        assertThatThrownBy(() -> sessionManager.startExam(studentSubmitted.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .matches(ex -> ((ResponseStatusException) ex).getStatusCode() == HttpStatus.CONFLICT);

        Student studentExpired = createStudent("sitting_expired");
        ExamStartResponse startExpired = sessionManager.startExam(studentExpired.getId());
        ExamSession sessionExpired = sessionRepository.findById(startExpired.sessionId()).orElseThrow();
        sessionExpired.setStatus(ExamSessionStatus.TIME_EXPIRED);
        sessionRepository.save(sessionExpired);

        assertThatThrownBy(() -> sessionManager.startExam(studentExpired.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .matches(ex -> ((ResponseStatusException) ex).getStatusCode() == HttpStatus.CONFLICT);

        Student studentLate = createStudent("sitting_late");
        ExamStartResponse startLate = sessionManager.startExam(studentLate.getId());
        ExamSession sessionLate = sessionRepository.findById(startLate.sessionId()).orElseThrow();
        sessionLate.setStatus(ExamSessionStatus.LATE_SUBMISSION_FLAGGED);
        sessionRepository.save(sessionLate);

        assertThatThrownBy(() -> sessionManager.startExam(studentLate.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .matches(ex -> ((ResponseStatusException) ex).getStatusCode() == HttpStatus.CONFLICT);
    }

    @Test
    void testMonotonicTabSwitchTrackingAndMalpractice403() {
        Student student = createStudent("tab_switch");
        ExamStartResponse startResp = sessionManager.startExam(student.getId());
        UUID sessionId = startResp.sessionId();

        Map<String, Object> state2 = new HashMap<>();
        state2.put("tabSwitchCount", 2);
        state2.put("answers", Map.of("q1", "A"));
        sessionManager.syncExam(sessionId, student.getId(), new ExamSyncRequest(state2));

        ExamSession sessionAfter2 = sessionRepository.findById(sessionId).orElseThrow();
        Number countAfter2 = (Number) sessionAfter2.getStatePayload().get("tabSwitchCount");
        assertThat(countAfter2.intValue()).isEqualTo(2);

        Map<String, Object> stateReset = new HashMap<>();
        stateReset.put("tabSwitchCount", 0);
        stateReset.put("answers", Map.of("q2", "B"));
        sessionManager.syncExam(sessionId, student.getId(), new ExamSyncRequest(stateReset));

        ExamSession sessionAfterReset = sessionRepository.findById(sessionId).orElseThrow();
        Number countAfterReset = (Number) sessionAfterReset.getStatePayload().get("tabSwitchCount");
        assertThat(countAfterReset.intValue()).isEqualTo(2);

        Map<String, Object> state3 = new HashMap<>();
        state3.put("tabSwitchCount", 3);
        assertThatThrownBy(() -> sessionManager.syncExam(sessionId, student.getId(), new ExamSyncRequest(state3)))
                .isInstanceOf(ResponseStatusException.class)
                .matches(ex -> ((ResponseStatusException) ex).getStatusCode() == HttpStatus.FORBIDDEN);

        ExamSession flaggedSession = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(flaggedSession.getStatus()).isEqualTo(ExamSessionStatus.FLAGGED_TAB_SWITCH);

        assertThatThrownBy(() -> sessionManager.startExam(student.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .matches(ex -> ((ResponseStatusException) ex).getStatusCode() == HttpStatus.FORBIDDEN);

        assertThatThrownBy(() -> sessionManager.getActivePayload(student.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .matches(ex -> ((ResponseStatusException) ex).getStatusCode() == HttpStatus.FORBIDDEN);
    }

    @Test
    void testExamExpirySchedulerSweepsOverdueSessions() {
        Student student = createStudent("scheduler_sweep");
        ExamStartResponse startResp = sessionManager.startExam(student.getId());
        UUID sessionId = startResp.sessionId();

        ExamSession session = sessionRepository.findById(sessionId).orElseThrow();
        session.setExpectedEndTime(Instant.now().minus(1200, ChronoUnit.SECONDS));
        sessionRepository.saveAndFlush(session);

        AopTestUtils.<ExamExpiryScheduler>getTargetObject(expiryScheduler).sweepExpiredSessions();

        ExamSession sweptSession = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(sweptSession.getStatus()).isEqualTo(ExamSessionStatus.TIME_EXPIRED);
    }
}
