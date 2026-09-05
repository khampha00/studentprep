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

import jakarta.persistence.ManyToOne;
import jakarta.persistence.JoinColumn;

@Getter
@Setter
@Entity
@Table(name = "questions")
public class Question extends BaseEntity {
    @ManyToOne
    @JoinColumn(name = "subject_id")
    private Subject subject;
    private String topic;
    private String status;

    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> content;
}
