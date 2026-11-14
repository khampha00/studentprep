package com.studentprep.student;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StudentRepository extends JpaRepository<Student, UUID> {
    Optional<Student> findByRegistrationNumber(String registrationNumber);
    Optional<Student> findByRegistrationNumberIgnoreCase(String registrationNumber);
    boolean existsByRegistrationNumber(String registrationNumber);
    boolean existsByRegistrationNumberIgnoreCase(String registrationNumber);

    @Query("SELECT sub.name, COUNT(st) FROM Student st JOIN st.subjects sub GROUP BY sub.name")
    List<Object[]> countSubjectRegistrations();
}
