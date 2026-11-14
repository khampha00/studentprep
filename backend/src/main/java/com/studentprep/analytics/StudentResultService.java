package com.studentprep.analytics;

import com.studentprep.student.Student;
import com.studentprep.student.StudentInternalAPI;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * Application service coordinating candidate exam result queries, ownership checks,
 * and result slip PDF packaging.
 */
@Service
@Transactional(readOnly = true)
public class StudentResultService {

    private final ExamResultRepository examResultRepository;
    private final StudentInternalAPI studentInternalAPI;
    private final ExamResultSummaryService examResultSummaryService;
    private final ExamResultSlipGenerator examResultSlipGenerator;

    public StudentResultService(
            ExamResultRepository examResultRepository,
            StudentInternalAPI studentInternalAPI,
            ExamResultSummaryService examResultSummaryService,
            ExamResultSlipGenerator examResultSlipGenerator) {
        this.examResultRepository = examResultRepository;
        this.studentInternalAPI = studentInternalAPI;
        this.examResultSummaryService = examResultSummaryService;
        this.examResultSlipGenerator = examResultSlipGenerator;
    }

    /**
     * Returns all graded exam results for the candidate identified by {@code registrationNumber}.
     */
    public List<StudentResultDto> getResultsForStudentRegistration(String registrationNumber) {
        Student student = requireStudentByRegistration(registrationNumber);
        return getResultsForStudent(student.getId());
    }

    /**
     * Returns the most recent graded exam result for the candidate identified by {@code registrationNumber}.
     */
    public Optional<LatestStudentResultDto> getLatestResultForStudentRegistration(String registrationNumber) {
        Student student = requireStudentByRegistration(registrationNumber);
        return examResultRepository.findFirstByStudentIdOrderByGradedAtDesc(student.getId())
                .map(result -> toLatestResultDto(result, student));
    }

    /**
     * Returns all graded exam results for a candidate by internal student UUID.
     */
    public List<StudentResultDto> getResultsForStudent(UUID studentId) {
        return examResultRepository.findByStudentIdOrderByGradedAtDesc(studentId).stream()
                .map(this::toResultDto)
                .toList();
    }

    /**
     * Generates a result slip PDF for {@code resultId} after verifying ownership by {@code registrationNumber}.
     */
    public GeneratedResultSlip generateStudentResultSlip(String registrationNumber, UUID resultId) {
        Student student = requireStudentByRegistration(registrationNumber);
        ExamResult result = requireResult(resultId, "Result not found");

        if (result.getStudent() == null || !result.getStudent().getId().equals(student.getId())) {
            throw new AccessDeniedException("Access denied to this result slip");
        }

        return renderSlip(result, student, result.getId());
    }

    /**
     * Generates the latest result slip PDF for the candidate identified by {@code registrationNumber}.
     */
    public GeneratedResultSlip generateLatestStudentResultSlip(String registrationNumber) {
        Student student = requireStudentByRegistration(registrationNumber);
        ExamResult latestResult = examResultRepository.findFirstByStudentIdOrderByGradedAtDesc(student.getId())
                .orElseThrow(() -> new NoSuchElementException("No exam results found for candidate"));

        return renderSlip(latestResult, student, latestResult.getId());
    }

    /**
     * Generates a result slip PDF for administrative access by {@code resultId}.
     */
    public GeneratedResultSlip generateAdminResultSlip(UUID resultId) {
        ExamResult result = requireResult(resultId, "Exam result not found");
        return renderSlip(result, result.getStudent(), resultId);
    }

    /**
     * Generates a result slip PDF for administrative access after verifying {@code resultId} belongs to {@code studentId}.
     */
    public GeneratedResultSlip generateAdminStudentResultSlip(UUID studentId, UUID resultId) {
        ExamResult result = requireResult(resultId, "Exam result not found");
        if (result.getStudent() == null || !result.getStudent().getId().equals(studentId)) {
            throw new NoSuchElementException("Exam result not found for candidate");
        }
        return renderSlip(result, result.getStudent(), resultId);
    }

    static String buildResultSlipFilename(Student student, UUID fallbackId) {
        String namePart = sanitizeFilenameSegment(student != null ? student.getName() : null, "Student");
        String fallbackReg = fallbackId != null ? fallbackId.toString() : "Result";
        String regPart = sanitizeFilenameSegment(
                student != null ? student.getRegistrationNumber() : null,
                fallbackReg);
        return namePart + "_" + regPart + "-Result-Slip.pdf";
    }

    private Student requireStudentByRegistration(String registrationNumber) {
        return studentInternalAPI.findByRegistrationNumberIgnoreCase(registrationNumber)
                .orElseThrow(() -> new NoSuchElementException("Student not found"));
    }

    private ExamResult requireResult(UUID resultId, String missingMessage) {
        return examResultRepository.findById(resultId)
                .orElseThrow(() -> new NoSuchElementException(missingMessage));
    }

    private GeneratedResultSlip renderSlip(ExamResult result, Student student, UUID fallbackId) {
        byte[] content = examResultSlipGenerator.generateResultSlipPdf(result);
        String filename = buildResultSlipFilename(student, fallbackId);
        return new GeneratedResultSlip(content, filename);
    }

    private StudentResultDto toResultDto(ExamResult result) {
        Student student = result.getStudent();
        return new StudentResultDto(
                result.getId(),
                student != null ? student.getName() : null,
                student != null ? student.getRegistrationNumber() : null,
                result.getTotalScore(),
                result.getMaxScore(),
                result.getTopicBreakdown(),
                examResultSummaryService.computeSubjectScores(result),
                formatGradedAt(result),
                result.getExamSessionId());
    }

    private LatestStudentResultDto toLatestResultDto(ExamResult result, Student student) {
        return new LatestStudentResultDto(
                result.getId(),
                student.getName(),
                student.getRegistrationNumber(),
                result.getTotalScore(),
                result.getMaxScore(),
                result.getTotalScore(),
                result.getMaxScore(),
                result.getTopicBreakdown(),
                examResultSummaryService.computeSubjectScores(result),
                formatGradedAt(result),
                result.getExamSessionId());
    }

    private static String formatGradedAt(ExamResult result) {
        return result.getGradedAt() != null ? result.getGradedAt().toString() : "";
    }

    private static String sanitizeFilenameSegment(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String sanitized = raw.trim().replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return sanitized.isEmpty() ? fallback : sanitized;
    }

    public record StudentResultDto(
            UUID id,
            String studentName,
            String registrationNumber,
            Integer totalScore,
            Integer maxScore,
            Map<String, TopicStats> topicBreakdown,
            Map<String, int[]> subjectScores,
            String gradedAt,
            UUID examSessionId) {
    }

    public record LatestStudentResultDto(
            UUID id,
            String studentName,
            String registrationNumber,
            Integer score,
            Integer total,
            Integer totalScore,
            Integer maxScore,
            Map<String, TopicStats> topicBreakdown,
            Map<String, int[]> subjectScores,
            String gradedAt,
            UUID examSessionId) {
    }

    public record GeneratedResultSlip(byte[] content, String filename) {
    }
}
