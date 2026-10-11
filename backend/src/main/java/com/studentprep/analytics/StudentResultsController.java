package com.studentprep.analytics;

import com.studentprep.common.ApiResponse;
import com.studentprep.student.Student;
import com.studentprep.student.StudentInternalAPI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
public class StudentResultsController {

    private final ExamResultRepository examResultRepository;
    private final StudentInternalAPI studentInternalAPI;
    private final ExamResultSlipGenerator examResultSlipGenerator;

    public StudentResultsController(ExamResultRepository examResultRepository, 
                                  StudentInternalAPI studentInternalAPI,
                                  ExamResultSlipGenerator examResultSlipGenerator) {
        this.examResultRepository = examResultRepository;
        this.studentInternalAPI = studentInternalAPI;
        this.examResultSlipGenerator = examResultSlipGenerator;
    }

    @GetMapping("/api/v1/students/me/results")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getMyResults(Authentication authentication) {
        String regNum = authentication.getName();
        Student student = studentInternalAPI.findByRegistrationNumberIgnoreCase(regNum)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Student not found"));
        return getStudentResults(student.getId());
    }

    @GetMapping("/api/v1/student/results")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getLatestStudentResult(Authentication authentication) {
        String regNum = authentication.getName();
        Student student = studentInternalAPI.findByRegistrationNumberIgnoreCase(regNum)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Student not found"));

        return examResultRepository.findFirstByStudentIdOrderByGradedAtDesc(student.getId())
                .map(r -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", r.getId());
                    map.put("score", r.getTotalScore());
                    map.put("total", r.getMaxScore());
                    map.put("totalScore", r.getTotalScore());
                    map.put("maxScore", r.getMaxScore());
                    map.put("topicBreakdown", r.getTopicBreakdown());
                    map.put("subjectScores", examResultSlipGenerator.computeSubjectScores(r));
                    map.put("gradedAt", r.getGradedAt() != null ? r.getGradedAt().toString() : "");
                    map.put("examSessionId", r.getExamSessionId());
                    return ResponseEntity.ok(ApiResponse.of(map));
                })
                .orElseGet(() -> ResponseEntity.ok(ApiResponse.of(null)));
    }

    @GetMapping("/api/v1/admin/students/{id}/results")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getStudentResults(@PathVariable UUID id) {
        List<ExamResult> results = examResultRepository.findByStudentIdOrderByGradedAtDesc(id);
        List<Map<String, Object>> mapped = results.stream().map(r -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", r.getId());
            map.put("totalScore", r.getTotalScore());
            map.put("maxScore", r.getMaxScore());
            map.put("topicBreakdown", r.getTopicBreakdown());
            map.put("subjectScores", examResultSlipGenerator.computeSubjectScores(r));
            map.put("gradedAt", r.getGradedAt() != null ? r.getGradedAt().toString() : "");
            map.put("examSessionId", r.getExamSessionId());
            return map;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.of(mapped));
    }

    @GetMapping(value = "/api/v1/students/me/results/{resultId}/slip", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> getMyResultSlip(@PathVariable UUID resultId, Authentication authentication) {
        String regNum = authentication.getName();
        Student student = studentInternalAPI.findByRegistrationNumberIgnoreCase(regNum)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Student not found"));

        ExamResult result = examResultRepository.findById(resultId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Result not found"));

        if (!result.getStudent().getId().equals(student.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied to this result slip");
        }

        byte[] pdfBytes = examResultSlipGenerator.generateResultSlipPdf(resultId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment", "Result-" + student.getRegistrationNumber() + ".pdf");
        headers.setContentLength(pdfBytes.length);

        return new ResponseEntity<>(pdfBytes, headers, HttpStatus.OK);
    }

    @GetMapping(value = "/api/v1/students/me/results/latest/slip", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> getMyLatestResultSlip(Authentication authentication) {
        String regNum = authentication.getName();
        Student student = studentInternalAPI.findByRegistrationNumberIgnoreCase(regNum)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Student not found"));

        ExamResult latestResult = examResultRepository.findFirstByStudentIdOrderByGradedAtDesc(student.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No exam results found for candidate"));

        byte[] pdfBytes = examResultSlipGenerator.generateResultSlipPdf(latestResult.getId());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment", "Result-" + student.getRegistrationNumber() + "-latest.pdf");
        headers.setContentLength(pdfBytes.length);

        return new ResponseEntity<>(pdfBytes, headers, HttpStatus.OK);
    }

    @GetMapping(value = "/api/v1/admin/results/{resultId}/slip", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> getAdminResultSlip(@PathVariable UUID resultId) {
        ExamResult result = examResultRepository.findById(resultId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Exam result not found"));

        byte[] pdfBytes = examResultSlipGenerator.generateResultSlipPdf(resultId);
        String regNum = result.getStudent() != null ? result.getStudent().getRegistrationNumber() : resultId.toString();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment", "StudentResult-" + regNum + ".pdf");
        headers.setContentLength(pdfBytes.length);

        return new ResponseEntity<>(pdfBytes, headers, HttpStatus.OK);
    }

    @GetMapping(value = "/api/v1/admin/students/{id}/results/{resultId}/slip", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> getAdminStudentResultSlip(@PathVariable UUID id, @PathVariable UUID resultId) {
        return getAdminResultSlip(resultId);
    }
}
