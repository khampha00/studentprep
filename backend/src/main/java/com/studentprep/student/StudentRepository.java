package com.studentprep.student;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StudentRepository extends JpaRepository<Student, UUID> {
    Optional<Student> findByRegistrationNumber(String registrationNumber);
    Optional<Student> findByRegistrationNumberIgnoreCase(String registrationNumber);
    boolean existsByRegistrationNumber(String registrationNumber);
    boolean existsByRegistrationNumberIgnoreCase(String registrationNumber);

    List<Student> findAllByOrderByNameAscRegistrationNumberAsc();
    List<Student> findByRegistrationNumberInOrderByNameAscRegistrationNumberAsc(Collection<String> registrationNumbers);

    Page<Student> findByPinResetRequired(boolean pinResetRequired, Pageable pageable);

    @Query("""
            SELECT s FROM Student s
            WHERE LOWER(s.name) LIKE :pattern ESCAPE '!'
               OR LOWER(s.registrationNumber) LIKE :pattern ESCAPE '!'
            """)
    Page<Student> searchByNameOrRegNumber(@Param("pattern") String pattern, Pageable pageable);

    @Query("""
            SELECT s FROM Student s
            WHERE s.pinResetRequired = :pinResetRequired
              AND (LOWER(s.name) LIKE :pattern ESCAPE '!'
                   OR LOWER(s.registrationNumber) LIKE :pattern ESCAPE '!')
            """)
    Page<Student> searchByNameOrRegNumberAndPinResetRequired(
            @Param("pattern") String pattern,
            @Param("pinResetRequired") boolean pinResetRequired,
            Pageable pageable);

    @Query("SELECT sub.name, COUNT(st) FROM Student st JOIN st.subjects sub GROUP BY sub.name")
    List<Object[]> countSubjectRegistrations();
}
