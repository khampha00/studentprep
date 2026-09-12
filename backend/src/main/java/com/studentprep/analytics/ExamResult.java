package com.studentprep.analytics;

import com.studentprep.student.Student;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "exam_results")
public class ExamResult {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @Column(name = "exam_session_id", nullable = false)
    private UUID examSessionId;

    @Column(name = "total_score", nullable = false)
    private Integer totalScore;

    @Column(name = "max_score", nullable = false)
    private Integer maxScore;

    @Type(JsonType.class)
    @Column(name = "topic_breakdown", columnDefinition = "jsonb")
    private Map<String, TopicStats> topicBreakdown;

    @CreationTimestamp
    @Column(name = "graded_at", updatable = false)
    private Instant gradedAt;
}
