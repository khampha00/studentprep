package com.studentprep.questionbank;

import com.studentprep.common.BaseEntity;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Type;

import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.FetchType;
import jakarta.persistence.Transient;

@Getter
@Setter
@Entity
@Table(name = "questions")
public class Question extends BaseEntity {
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "subject_id")
    @JsonIgnore
    private Subject subject;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "context_id")
    private QuestionContext context;

    @Column(name = "ingestion_job_id")
    private UUID ingestionJobId;

    @Transient
    private UUID transientSubjectId;

    @Transient
    private UUID transientContextId;

    @JsonProperty("subjectId")
    public UUID getSubjectId() {
        if (subject != null) return subject.getId();
        return transientSubjectId;
    }

    @JsonProperty("subjectId")
    public void setSubjectId(UUID subjectId) {
        this.transientSubjectId = subjectId;
    }

    @JsonProperty("contextId")
    public UUID getContextId() {
        if (context != null) return context.getId();
        return transientContextId;
    }

    @JsonProperty("contextId")
    public void setContextId(UUID contextId) {
        this.transientContextId = contextId;
    }

    private String topic;
    @Enumerated(EnumType.STRING)
    private QuestionStatus status;

    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> content;
}
