package com.studentprep.common;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class OutboxRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxRecoveryScheduler.class);

    private final IncompleteEventPublications incompletePublications;
    private final Duration olderThan;

    public OutboxRecoveryScheduler(
            IncompleteEventPublications incompletePublications,
            @Value("${exam.outbox.older-than-seconds:60}") long olderThanSeconds) {
        this.incompletePublications = incompletePublications;
        this.olderThan = Duration.ofSeconds(olderThanSeconds);
    }

    @Scheduled(fixedDelayString = "${exam.outbox.recovery-interval-ms:60000}")
    @SchedulerLock(name = "outbox_recovery_sweep", lockAtMostFor = "5m", lockAtLeastFor = "10s")
    public void sweepIncompletePublications() {
        log.debug("Sweeping incomplete event publications older than {}", olderThan);
        try {
            incompletePublications.resubmitIncompletePublicationsOlderThan(olderThan);
        } catch (Exception ex) {
            log.warn("Failed to resubmit incomplete event publications: {}", ex.getMessage());
        }
    }
}
