package com.studentprep.analytics;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.studentprep.student.Student;
import com.studentprep.student.StudentRepository;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
public class AnalyticsControllerTest {

    @Autowired AnalyticsController controller;
    @Autowired ExamResultRepository repo;
    @Autowired StudentRepository studentRepo;

    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Test
    public void test() {
        UUID studentId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        
        jdbcTemplate.update("INSERT INTO students (id, name, state, registration_number, pin_hash) VALUES (?, 'T', 'ST', 'R123', 'H')", studentId);
        jdbcTemplate.update("INSERT INTO users (id, identifier, pin_hash, role) VALUES (?, 'T123', 'H', 'ROLE_STUDENT')", studentId);
        jdbcTemplate.update("INSERT INTO exam_sessions (id, user_id, status) VALUES (?, ?, 'COMPLETED')", sessionId, studentId);
        
        ExamResult r = new ExamResult();
        r.setExamSessionId(sessionId);
        
        Student s = new Student();
        s.setId(studentId);
        r.setStudent(s);
        
        r.setMaxScore(0);
        r.setTotalScore(0);
        repo.save(r);
        
        controller.getDashboard();
    }
}
