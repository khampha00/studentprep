package com.studentprep.exam;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Component
public class ExamExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExamExpiryScheduler.class);

    private final ExamSessionRepository sessionRepository;
    private final ExamSessionManager sessionManager;
    private final ExamTimingProperties timingProperties;

    public ExamExpiryScheduler(
            ExamSessionRepository sessionRepository,
            ExamSessionManager sessionManager,
            ExamTimingProperties timingProperties
    ) {
        this.sessionRepository = sessionRepository;
        this.sessionManager = sessionManager;
        this.timingProperties = timingProperties;
    }

    @Scheduled(fixedDelayString = "${exam.expiry.sweep-interval-ms:30000}")
    @SchedulerLock(name = "exam_expiry_sweep", lockAtMostFor = "PT2M", lockAtLeastFor = "PT0S")
    public void sweepExpiredSessions() {
        long offlineGrace = timingProperties.getOfflineSubmitGraceSeconds();
        Instant cutoff = Instant.now().minus(offlineGrace, ChronoUnit.SECONDS);

        List<ExamSession> expiredSessions = sessionRepository.findExpiredInProgressSessions(cutoff);
        if (expiredSessions.isEmpty()) {
            return;
        }

        log.info("Exam expiry sweep found {} expired in-progress session(s) past cutoff {}", expiredSessions.size(), cutoff);
        for (ExamSession session : expiredSessions) {
            try {
                sessionManager.expireSessionByScheduler(session.getId());
            } catch (Exception ex) {
                log.error("Failed to expire session {}: {}", session.getId(), ex.getMessage(), ex);
            }
        }
    }
}
