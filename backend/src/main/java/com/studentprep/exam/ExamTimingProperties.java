package com.studentprep.exam;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "exam.timing")
public class ExamTimingProperties {

    private long durationMinutes = 120;
    private long syncGraceSeconds = 30;
    private long offlineSubmitGraceSeconds = 900;

    public ExamTimingProperties() {
    }

    public ExamTimingProperties(long durationMinutes, long syncGraceSeconds, long offlineSubmitGraceSeconds) {
        this.durationMinutes = durationMinutes;
        this.syncGraceSeconds = syncGraceSeconds;
        this.offlineSubmitGraceSeconds = offlineSubmitGraceSeconds;
    }

    public long getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(long durationMinutes) {
        this.durationMinutes = durationMinutes;
    }

    public long getSyncGraceSeconds() {
        return syncGraceSeconds;
    }

    public void setSyncGraceSeconds(long syncGraceSeconds) {
        this.syncGraceSeconds = syncGraceSeconds;
    }

    public long getOfflineSubmitGraceSeconds() {
        return offlineSubmitGraceSeconds;
    }

    public void setOfflineSubmitGraceSeconds(long offlineSubmitGraceSeconds) {
        this.offlineSubmitGraceSeconds = offlineSubmitGraceSeconds;
    }

    public long durationMinutes() {
        return durationMinutes;
    }

    public long syncGraceSeconds() {
        return syncGraceSeconds;
    }

    public long offlineSubmitGraceSeconds() {
        return offlineSubmitGraceSeconds;
    }
}
