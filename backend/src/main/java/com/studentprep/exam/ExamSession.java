package com.studentprep.exam;

import com.studentprep.common.BaseEntity;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

    private Instant startTime;
    private Instant endTime;
    private String status;
    private Integer shuffleSeed;

    @Version
    private Long version;

    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> statePayload;
}
