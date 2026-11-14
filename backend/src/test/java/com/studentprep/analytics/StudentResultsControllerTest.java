package com.studentprep.analytics;

import com.studentprep.analytics.StudentResultService.GeneratedResultSlip;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.TestingAuthenticationToken;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StudentResultsControllerTest {

    @Mock
    private StudentResultService studentResultService;

    private StudentResultsController controller;

    @BeforeEach
    void setUp() {
        controller = new StudentResultsController(studentResultService);
    }

    @Test
    void slipEndpoints_DelegateToServiceAndSetPdfAttachmentHeaders() {
        UUID studentId = UUID.randomUUID();
        UUID resultId = UUID.randomUUID();
        byte[] samplePdf = "%PDF-sample".getBytes(StandardCharsets.US_ASCII);
        String expectedFilename = "Ada_E2E_O_Connor_710920XX-Result-Slip.pdf";
        GeneratedResultSlip slip = new GeneratedResultSlip(samplePdf, expectedFilename);

        when(studentResultService.generateStudentResultSlip("710920XX", resultId)).thenReturn(slip);
        when(studentResultService.generateLatestStudentResultSlip("710920XX")).thenReturn(slip);
        when(studentResultService.generateAdminResultSlip(resultId)).thenReturn(slip);
        when(studentResultService.generateAdminStudentResultSlip(studentId, resultId)).thenReturn(slip);

        TestingAuthenticationToken auth = new TestingAuthenticationToken("710920XX", null, "ROLE_STUDENT");

        for (ResponseEntity<byte[]> response : new ResponseEntity[]{
                controller.getMyResultSlip(resultId, auth),
                controller.getMyLatestResultSlip(auth),
                controller.getAdminResultSlip(resultId),
                controller.getAdminStudentResultSlip(studentId, resultId)
        }) {
            assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
            assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                    .contains(expectedFilename);
            assertThat(response.getBody()).isEqualTo(samplePdf);
        }
    }
}
