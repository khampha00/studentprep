package com.studentprep.exam;

import com.studentprep.common.BaseEntity;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "exam_sessions")
public class ExamSession extends BaseEntity {
    
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "start_time")
    private Instant startTime;

    @Column(name = "end_time")
    private Instant endTime;

    @Column(name = "expected_end_time")
    private Instant expectedEndTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private ExamSessionStatus status;

    @Column(name = "shuffle_seed")
    private Integer shuffleSeed;

    @Version
    private Long version;

    @Type(JsonType.class)
    @Column(name = "state_payload", columnDefinition = "jsonb")
    private Map<String, Object> statePayload;

    @Type(JsonType.class)
    @Column(name = "snapshot_payload", columnDefinition = "jsonb")
    private Map<String, Object> snapshotPayload;

    @Column(name = "superseded", nullable = false)
    private boolean superseded = false;
}
