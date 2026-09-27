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

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.FetchType;

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

    @jakarta.persistence.Transient
    private java.util.UUID transientSubjectId;

    @jakarta.persistence.Transient
    private java.util.UUID transientContextId;

    @com.fasterxml.jackson.annotation.JsonProperty("subjectId")
    public java.util.UUID getSubjectId() {
        if (subject != null) return subject.getId();
        return transientSubjectId;
    }

    @com.fasterxml.jackson.annotation.JsonProperty("subjectId")
    public void setSubjectId(java.util.UUID subjectId) {
        this.transientSubjectId = subjectId;
    }

    @com.fasterxml.jackson.annotation.JsonProperty("contextId")
    public java.util.UUID getContextId() {
        if (context != null) return context.getId();
        return transientContextId;
    }

    @com.fasterxml.jackson.annotation.JsonProperty("contextId")
    public void setContextId(java.util.UUID contextId) {
        this.transientContextId = contextId;
    }

    private String topic;
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private QuestionStatus status;

    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> content;
}
