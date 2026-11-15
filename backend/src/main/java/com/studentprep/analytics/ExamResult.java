package com.studentprep.analytics;

import com.studentprep.student.Student;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
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

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @Column(name = "exam_session_id", nullable = false, unique = true)
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

    @Enumerated(EnumType.STRING)
    @Column(name = "moderation_status", nullable = false)
    private ExamResultModerationStatus moderationStatus = ExamResultModerationStatus.ELIGIBLE;

    @Column(name = "moderated_by")
    private String moderatedBy;

    @Column(name = "moderated_at")
    private Instant moderatedAt;

    @Column(name = "moderation_note")
    private String moderationNote;
}
