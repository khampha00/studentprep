package com.studentprep.exam;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import com.studentprep.student.StudentInternalAPI;

@Service
public class ExamInternalAPIImpl implements ExamInternalAPI {

    private final ExamSessionRepository sessionRepository;
    private final StudentInternalAPI studentInternalAPI;

    public ExamInternalAPIImpl(ExamSessionRepository sessionRepository, StudentInternalAPI studentInternalAPI) {
        this.sessionRepository = sessionRepository;
        this.studentInternalAPI = studentInternalAPI;
    }

    @Override
    public boolean hasFlaggedSession(UUID studentId) {
        return sessionRepository.existsByUserIdAndStatus(studentId, ExamSessionStatus.FLAGGED_TAB_SWITCH);
    }

    @Override
    public boolean hasActiveSession(UUID studentId) {
        return sessionRepository.findByUserIdAndStatus(studentId, ExamSessionStatus.IN_PROGRESS).isPresent();
    }

    @Override
    public List<Map<String, Object>> getLiveSessions() {
        return sessionRepository.findAllByStatus(ExamSessionStatus.IN_PROGRESS).stream().map(s -> {
            String studentName = studentInternalAPI.findById(s.getUserId())
                    .map(com.studentprep.student.Student::getName)
                    .orElse("Unknown Student");
            
            Object timeLeftObj = s.getStatePayload() != null ? s.getStatePayload().get("timeLeft") : 7200;
            int timeLeft = 7200;
            if (timeLeftObj instanceof Number) {
                timeLeft = ((Number) timeLeftObj).intValue();
            }
            
            return Map.<String, Object>of(
                    "sessionId", s.getId().toString(),
                    "studentName", studentName,
                    "timeLeft", timeLeft,
                    "status", s.getStatus().name()
            );
        }).collect(Collectors.toList());
    }

    @Override
    public long countCompletedExamsBySessionId(UUID examId) {
        return 0; // Handled by analytics module, wait, ExamInternalAPI is for others to query exam module, but exam module doesn't own results.
    }
}
