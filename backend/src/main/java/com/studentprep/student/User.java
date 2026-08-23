package com.studentprep.student;

import com.studentprep.common.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "users")
public class User extends BaseEntity {
    private String identifier;
    private String pinHash;
    private String role;
}
