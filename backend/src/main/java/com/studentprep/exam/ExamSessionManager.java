package com.studentprep.exam;

import com.studentprep.exam.dto.ExamPayloadResponse;
import com.studentprep.exam.dto.ExamStartResponse;
import com.studentprep.exam.dto.ExamSyncRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class ExamSessionManager {

    private static final Logger log = LoggerFactory.getLogger(ExamSessionManager.class);

    private final ExamSessionRepository sessionRepository;
    private final ExamPayloadGenerator payloadGenerator;
    private final ApplicationEventPublisher eventPublisher;
    private final ExamTimingProperties timingProperties;

    public ExamSessionManager(
            ExamSessionRepository sessionRepository,
            ExamPayloadGenerator payloadGenerator,
            ApplicationEventPublisher eventPublisher,
            ExamTimingProperties timingProperties
    ) {
        this.sessionRepository = sessionRepository;
        this.payloadGenerator = payloadGenerator;
        this.eventPublisher = eventPublisher;
        this.timingProperties = timingProperties;
    }

    @Transactional(noRollbackFor = {ResponseStatusException.class, ExamDeadlineExpiredException.class})
    public ExamStartResponse startExam(UUID userId) {
        if (hasActiveMalpractice(userId)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Your account has been blocked due to exam malpractice. You cannot start an exam."
            );
        }

        List<ExamSession> userSessions = sessionRepository.findAllByUserIdAndSupersededFalse(userId);
        for (ExamSession s : userSessions) {
            if (s.getStatus() == ExamSessionStatus.SUBMITTED
                    || s.getStatus() == ExamSessionStatus.TIME_EXPIRED
                    || s.getStatus() == ExamSessionStatus.LATE_SUBMISSION_FLAGGED) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Candidate has already completed or expired their examination sitting."
                );
            }
        }

        Instant now = Instant.now();
        Optional<ExamSession> existingSession = sessionRepository.findByUserIdAndStatusAndSupersededFalse(userId, ExamSessionStatus.IN_PROGRESS);
        if (existingSession.isPresent()) {
            return resumeExistingSession(existingSession.get(), userId, now);
        }

        int shuffleSeed = ThreadLocalRandom.current().nextInt(1000, 9999);
        Map<String, Object> snapshotPayload = payloadGenerator.buildSessionSnapshot(userId, shuffleSeed);

        long durationMinutes = timingProperties.getDurationMinutes();
        Instant expectedEndTime = now.plus(durationMinutes, ChronoUnit.MINUTES);

        ExamSession session = new ExamSession();
        session.setUserId(userId);
        session.setStartTime(now);
        session.setExpectedEndTime(expectedEndTime);
        session.setStatus(ExamSessionStatus.IN_PROGRESS);
        session.setShuffleSeed(shuffleSeed);
        session.setSnapshotPayload(snapshotPayload);
        session.setSuperseded(false);

        Map<String, Object> initialState = new HashMap<>();
        initialState.put("answers", new HashMap<>());
        initialState.put("timeLeft", (int) (durationMinutes * 60));
        initialState.put("lastUpdated", System.currentTimeMillis());
        initialState.put("tabSwitchCount", 0);
        session.setStatePayload(initialState);

        try {
            session = sessionRepository.save(session);
        } catch (DataIntegrityViolationException ex) {
            Optional<ExamSession> concurrent = sessionRepository.findByUserIdAndStatusAndSupersededFalse(userId, ExamSessionStatus.IN_PROGRESS);
            if (concurrent.isPresent()) {
                return resumeExistingSession(concurrent.get(), userId, now);
            }
            throw ex;
        }

        ExamPayloadResponse strippedPayload = payloadGenerator.projectFromSnapshot(snapshotPayload);
        Map<String, Object> payloadMap = buildStartPayloadMap(strippedPayload, initialState, false);

        return new ExamStartResponse(
                session.getId(),
                shuffleSeed,
                expectedEndTime,
                now,
                payloadMap,
                initialState,
                false
        );
    }

    private ExamStartResponse resumeExistingSession(ExamSession existing, UUID userId, Instant now) {
        Instant expectedEndTime = existing.getExpectedEndTime() != null
                ? existing.getExpectedEndTime()
                : existing.getStartTime().plus(timingProperties.getDurationMinutes(), ChronoUnit.MINUTES);

        long syncGrace = timingProperties.getSyncGraceSeconds();
        if (now.isAfter(expectedEndTime.plus(syncGrace, ChronoUnit.SECONDS))) {
            finalizeExamSession(existing, ExamSessionStatus.TIME_EXPIRED);
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Your exam duration has expired. Session has been submitted."
            );
        }

        long elapsedSeconds = ChronoUnit.SECONDS.between(existing.getStartTime(), now);
        int remainingSeconds = (int) Math.max(0, (timingProperties.getDurationMinutes() * 60) - elapsedSeconds);

        Map<String, Object> statePayload = existing.getStatePayload() != null
                ? new HashMap<>(existing.getStatePayload())
                : new HashMap<>();

        statePayload.putIfAbsent("answers", new HashMap<>());
        statePayload.putIfAbsent("tabSwitchCount", 0);
        statePayload.putIfAbsent("lastUpdated", System.currentTimeMillis());

        if (statePayload.containsKey("timeLeft") && statePayload.get("timeLeft") instanceof Number num) {
            int savedTime = num.intValue();
            statePayload.put("timeLeft", Math.min(savedTime, remainingSeconds));
        } else {
            statePayload.put("timeLeft", remainingSeconds);
        }
        existing.setStatePayload(statePayload);
        sessionRepository.save(existing);

        Map<String, Object> snapshot = existing.getSnapshotPayload();
        if (snapshot == null) {
            snapshot = payloadGenerator.buildSessionSnapshot(userId, existing.getShuffleSeed());
            existing.setSnapshotPayload(snapshot);
            sessionRepository.save(existing);
        }

        ExamPayloadResponse strippedPayload = payloadGenerator.projectFromSnapshot(snapshot);
        Map<String, Object> payloadMap = buildStartPayloadMap(strippedPayload, statePayload, true);

        int shuffleSeed = existing.getShuffleSeed() != null ? existing.getShuffleSeed() : 1234;
        return new ExamStartResponse(
                existing.getId(),
                shuffleSeed,
                expectedEndTime,
                now,
                payloadMap,
                statePayload,
                true
        );
    }

    private Map<String, Object> buildStartPayloadMap(
            ExamPayloadResponse strippedPayload,
            Map<String, Object> stateMap,
            boolean resumed
    ) {
        Map<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("questions", strippedPayload.getQuestions());
        payloadMap.put("contexts", strippedPayload.getContexts());
        payloadMap.put("contextAssets", strippedPayload.getContextAssets() != null
                ? strippedPayload.getContextAssets()
                : new HashMap<>());
        payloadMap.put("durationMinutes", strippedPayload.getDurationMinutes());
        payloadMap.put("resumed", resumed);
        payloadMap.put("answers", stateMap.get("answers"));
        payloadMap.put("timeLeft", stateMap.get("timeLeft"));
        payloadMap.put("tabSwitchCount", stateMap.get("tabSwitchCount"));
        payloadMap.put("student", strippedPayload.getStudent());
        return payloadMap;
    }

    private ExamSession resolveSession(UUID sessionId, UUID studentId) {
        if (sessionId != null) {
            Optional<ExamSession> sessionOpt = sessionRepository.findById(sessionId);
            if (sessionOpt.isPresent()) {
                ExamSession s = sessionOpt.get();
                if (!s.getUserId().equals(studentId)) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access Denied");
                }
                return s;
            }
        }
        return sessionRepository.findByUserIdAndStatusAndSupersededFalse(studentId, ExamSessionStatus.IN_PROGRESS)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No active exam session found"));
    }

    @Transactional(noRollbackFor = {ResponseStatusException.class, ExamDeadlineExpiredException.class})
    public void syncExam(UUID sessionId, UUID studentId, ExamSyncRequest request) {
        if (request == null || request.statePayload() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "statePayload is required");
        }

        ExamSession session = resolveSession(sessionId, studentId);

        if (session.getStatus() == ExamSessionStatus.FLAGGED_TAB_SWITCH) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "MALPRACTICE_BLOCKED");
        }

        boolean isFinal = Boolean.TRUE.equals(request.statePayload().get("isFinal"));

        if (isTerminal(session.getStatus())) {
            if (isFinal) {
                return;
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "SESSION_ALREADY_FINALIZED");
        }

        int existingTabSwitches = 0;
        if (session.getStatePayload() != null && session.getStatePayload().get("tabSwitchCount") instanceof Number num) {
            existingTabSwitches = num.intValue();
        }
        int reportedTabSwitches = 0;
        if (request.statePayload().get("tabSwitchCount") instanceof Number num) {
            reportedTabSwitches = num.intValue();
        }
        int effectiveTabSwitches = Math.max(existingTabSwitches, reportedTabSwitches);

        Map<String, Object> mergedAnswers = new HashMap<>();
        if (session.getStatePayload() != null && session.getStatePayload().get("answers") instanceof Map<?, ?> oldAnswers) {
            for (Map.Entry<?, ?> entry : oldAnswers.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    mergedAnswers.put(entry.getKey().toString(), entry.getValue());
                }
            }
        }
        if (request.statePayload().get("answers") instanceof Map<?, ?> newAnswers) {
            for (Map.Entry<?, ?> entry : newAnswers.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    mergedAnswers.put(entry.getKey().toString(), entry.getValue());
                }
            }
        }

        Map<String, Object> updatedState = new HashMap<>(request.statePayload());
        updatedState.put("tabSwitchCount", effectiveTabSwitches);
        updatedState.put("answers", mergedAnswers);
        session.setStatePayload(updatedState);

        String reportedReason = (String) request.statePayload().get("reason");
        if (effectiveTabSwitches >= 3 || ExamSessionStatus.FLAGGED_TAB_SWITCH.name().equals(reportedReason)) {
            finalizeExamSession(session, ExamSessionStatus.FLAGGED_TAB_SWITCH);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "MALPRACTICE_BLOCKED");
        }

        Instant expectedEndTime = session.getExpectedEndTime() != null
                ? session.getExpectedEndTime()
                : session.getStartTime().plus(timingProperties.getDurationMinutes(), ChronoUnit.MINUTES);
        Instant now = Instant.now();
        long syncGrace = timingProperties.getSyncGraceSeconds();
        long offlineGrace = timingProperties.getOfflineSubmitGraceSeconds();

        if (isFinal) {
            if (now.isAfter(expectedEndTime.plus(offlineGrace, ChronoUnit.SECONDS))) {
                finalizeExamSession(session, ExamSessionStatus.TIME_EXPIRED);
                throw new ExamDeadlineExpiredException("Offline submission grace period exceeded", "OFFLINE_CUTOFF_EXCEEDED");
            }
            if (now.isAfter(expectedEndTime.plus(syncGrace, ChronoUnit.SECONDS))) {
                finalizeExamSession(session, ExamSessionStatus.LATE_SUBMISSION_FLAGGED);
            } else {
                finalizeExamSession(session, ExamSessionStatus.SUBMITTED);
            }
            return;
        }

        if (now.isAfter(expectedEndTime.plus(syncGrace, ChronoUnit.SECONDS))) {
            finalizeExamSession(session, ExamSessionStatus.TIME_EXPIRED);
            throw new ExamDeadlineExpiredException("Exam submission window has expired", "EXAM_WINDOW_EXPIRED");
        }

        sessionRepository.save(session);
    }

    @Transactional(noRollbackFor = {ResponseStatusException.class, ExamDeadlineExpiredException.class})
    public ExamSessionStatus submitExam(UUID sessionId, UUID studentId) {
        return submitExam(sessionId, studentId, ExamSessionStatus.NORMAL);
    }

    @Transactional(noRollbackFor = {ResponseStatusException.class, ExamDeadlineExpiredException.class})
    public ExamSessionStatus submitExam(UUID sessionId, UUID studentId, ExamSessionStatus reasonParam) {
        ExamSession session = resolveSession(sessionId, studentId);

        if (session.getStatus() == ExamSessionStatus.FLAGGED_TAB_SWITCH) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "MALPRACTICE_BLOCKED");
        }

        if (isTerminal(session.getStatus())) {
            return session.getStatus();
        }

        if (reasonParam == ExamSessionStatus.FLAGGED_TAB_SWITCH) {
            finalizeExamSession(session, ExamSessionStatus.FLAGGED_TAB_SWITCH);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "MALPRACTICE_BLOCKED");
        }

        Instant expectedEndTime = session.getExpectedEndTime() != null
                ? session.getExpectedEndTime()
                : session.getStartTime().plus(timingProperties.getDurationMinutes(), ChronoUnit.MINUTES);
        Instant now = Instant.now();
        long syncGrace = timingProperties.getSyncGraceSeconds();
        long offlineGrace = timingProperties.getOfflineSubmitGraceSeconds();

        if (now.isAfter(expectedEndTime.plus(offlineGrace, ChronoUnit.SECONDS))) {
            finalizeExamSession(session, ExamSessionStatus.TIME_EXPIRED);
            throw new ExamDeadlineExpiredException("Offline submission grace period exceeded", "OFFLINE_CUTOFF_EXCEEDED");
        }

        if (now.isAfter(expectedEndTime.plus(syncGrace, ChronoUnit.SECONDS))) {
            finalizeExamSession(session, ExamSessionStatus.LATE_SUBMISSION_FLAGGED);
            return ExamSessionStatus.LATE_SUBMISSION_FLAGGED;
        }

        ExamSessionStatus finalStatus = (reasonParam == ExamSessionStatus.TIME_EXPIRED)
                ? ExamSessionStatus.TIME_EXPIRED
                : ExamSessionStatus.SUBMITTED;
        finalizeExamSession(session, finalStatus);
        return finalStatus;
    }

    private void finalizeExamSession(ExamSession session, ExamSessionStatus terminalStatus) {
        if (isTerminal(session.getStatus())) {
            return;
        }

        session.setStatus(terminalStatus);
        session.setEndTime(Instant.now());
        sessionRepository.saveAndFlush(session);

        if (terminalStatus == ExamSessionStatus.FLAGGED_TAB_SWITCH) {
            eventPublisher.publishEvent(new ExamSessionFlaggedEvent(session.getId(), session.getUserId(), "TAB_SWITCH_LIMIT_EXCEEDED"));
        } else {
            eventPublisher.publishEvent(new ExamSubmittedEvent(
                    session.getId(),
                    session.getUserId(),
                    session.getStatePayload() != null ? session.getStatePayload() : Collections.emptyMap(),
                    terminalStatus,
                    session.getSnapshotPayload() != null ? session.getSnapshotPayload() : Collections.emptyMap()
            ));
        }
    }

    private boolean isTerminal(ExamSessionStatus status) {
        return status == ExamSessionStatus.SUBMITTED
                || status == ExamSessionStatus.TIME_EXPIRED
                || status == ExamSessionStatus.LATE_SUBMISSION_FLAGGED
                || status == ExamSessionStatus.FLAGGED_TAB_SWITCH;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getActiveSession(UUID userId) {
        return sessionRepository.findByUserIdAndStatusAndSupersededFalse(userId, ExamSessionStatus.IN_PROGRESS)
                .map(session -> {
                    Instant expectedEndTime = session.getExpectedEndTime() != null
                            ? session.getExpectedEndTime()
                            : session.getStartTime().plus(timingProperties.getDurationMinutes(), ChronoUnit.MINUTES);
                    Instant now = Instant.now();
                    long syncGrace = timingProperties.getSyncGraceSeconds();
                    if (now.isAfter(expectedEndTime.plus(syncGrace, ChronoUnit.SECONDS))) {
                        return null;
                    }

                    Map<String, Object> result = new HashMap<>();
                    result.put("sessionId", session.getId());
                    result.put("status", session.getStatus().name());
                    result.put("startTime", session.getStartTime().toString());
                    result.put("expectedEndTime", expectedEndTime.toString());
                    result.put("serverTime", now.toString());
                    result.put("shuffleSeed", session.getShuffleSeed());

                    long elapsedSeconds = ChronoUnit.SECONDS.between(session.getStartTime(), now);
                    int wallClockRemaining = (int) Math.max(0, (timingProperties.getDurationMinutes() * 60) - elapsedSeconds);

                    if (session.getStatePayload() != null) {
                        result.put("answers", session.getStatePayload().getOrDefault("answers", new HashMap<>()));
                        Object savedTime = session.getStatePayload().get("timeLeft");
                        if (savedTime instanceof Number num) {
                            result.put("timeLeft", Math.min(num.intValue(), wallClockRemaining));
                        } else {
                            result.put("timeLeft", wallClockRemaining);
                        }
                        result.put("lastSyncedAt", session.getStatePayload().getOrDefault("lastUpdated", System.currentTimeMillis()));
                        result.put("tabSwitchCount", session.getStatePayload().getOrDefault("tabSwitchCount", 0));
                    } else {
                        result.put("answers", new HashMap<>());
                        result.put("timeLeft", wallClockRemaining);
                        result.put("lastSyncedAt", System.currentTimeMillis());
                        result.put("tabSwitchCount", 0);
                    }
                    return result;
                })
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public ExamPayloadResponse getActivePayload(UUID studentId) {
        if (hasActiveMalpractice(studentId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "MALPRACTICE_BLOCKED");
        }
        Optional<ExamSession> sessionOpt = sessionRepository.findByUserIdAndStatusAndSupersededFalse(studentId, ExamSessionStatus.IN_PROGRESS);
        if (sessionOpt.isPresent() && sessionOpt.get().getSnapshotPayload() != null) {
            return payloadGenerator.projectFromSnapshot(sessionOpt.get().getSnapshotPayload());
        }
        return payloadGenerator.getActivePayload(studentId);
    }

    @Transactional
    public void expireSessionByScheduler(UUID sessionId) {
        Optional<ExamSession> opt = sessionRepository.findById(sessionId);
        if (opt.isEmpty()) {
            return;
        }
        ExamSession session = opt.get();
        if (session.getStatus() == ExamSessionStatus.IN_PROGRESS && !session.isSuperseded()) {
            Instant expectedEndTime = session.getExpectedEndTime() != null
                    ? session.getExpectedEndTime()
                    : session.getStartTime().plus(timingProperties.getDurationMinutes(), ChronoUnit.MINUTES);
            Instant now = Instant.now();
            if (now.isAfter(expectedEndTime.plus(timingProperties.getOfflineSubmitGraceSeconds(), ChronoUnit.SECONDS))) {
                finalizeExamSession(session, ExamSessionStatus.TIME_EXPIRED);
                log.info("Finalized expired exam session {} as TIME_EXPIRED by scheduler", session.getId());
            }
        }
    }

    public boolean hasActiveMalpractice(UUID studentId) {
        if (studentId == null) {
            return false;
        }
        return sessionRepository.existsByUserIdAndStatusAndSupersededFalse(studentId, ExamSessionStatus.FLAGGED_TAB_SWITCH);
    }
}
