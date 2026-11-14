package com.studentprep.analytics;

import com.studentprep.analytics.StudentResultService.GeneratedResultSlip;
import com.studentprep.analytics.StudentResultService.LatestStudentResultDto;
import com.studentprep.analytics.StudentResultService.StudentResultDto;
import com.studentprep.common.ApiResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Thin HTTP controller exposing candidate and administrative exam result endpoints.
 */
@RestController
public class StudentResultsController {

    private final StudentResultService studentResultService;

    public StudentResultsController(StudentResultService studentResultService) {
        this.studentResultService = studentResultService;
    }

    @GetMapping("/api/v1/students/me/results")
    public ResponseEntity<ApiResponse<List<StudentResultDto>>> getMyResults(Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.of(
                studentResultService.getResultsForStudentRegistration(authentication.getName())));
    }

    @GetMapping("/api/v1/student/results")
    public ResponseEntity<ApiResponse<LatestStudentResultDto>> getLatestStudentResult(
            Authentication authentication) {
        LatestStudentResultDto latest = studentResultService
                .getLatestResultForStudentRegistration(authentication.getName())
                .orElse(null);
        return ResponseEntity.ok(ApiResponse.of(latest));
    }

    @GetMapping("/api/v1/admin/students/{id}/results")
    public ResponseEntity<ApiResponse<List<StudentResultDto>>> getStudentResults(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.of(studentResultService.getResultsForStudent(id)));
    }

    @GetMapping(value = "/api/v1/students/me/results/{resultId}/slip", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> getMyResultSlip(
            @PathVariable UUID resultId,
            Authentication authentication) {
        return toPdfAttachmentResponse(
                studentResultService.generateStudentResultSlip(authentication.getName(), resultId));
    }

    @GetMapping(value = "/api/v1/students/me/results/latest/slip", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> getMyLatestResultSlip(Authentication authentication) {
        return toPdfAttachmentResponse(
                studentResultService.generateLatestStudentResultSlip(authentication.getName()));
    }

    @GetMapping(value = "/api/v1/admin/results/{resultId}/slip", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> getAdminResultSlip(@PathVariable UUID resultId) {
        return toPdfAttachmentResponse(studentResultService.generateAdminResultSlip(resultId));
    }

    @GetMapping(value = "/api/v1/admin/students/{id}/results/{resultId}/slip", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> getAdminStudentResultSlip(
            @PathVariable UUID id,
            @PathVariable UUID resultId) {
        return toPdfAttachmentResponse(studentResultService.generateAdminStudentResultSlip(id, resultId));
    }

    private static ResponseEntity<byte[]> toPdfAttachmentResponse(GeneratedResultSlip slip) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment", slip.filename());
        headers.setContentLength(slip.content().length);
        return new ResponseEntity<>(slip.content(), headers, HttpStatus.OK);
    }
}
