package com.studentprep.analytics;

import com.studentprep.analytics.StudentResultService.GeneratedResultSlip;
import com.studentprep.analytics.StudentResultService.LatestStudentResultDto;
import com.studentprep.analytics.StudentResultService.StudentResultDto;
import com.studentprep.student.Student;
import com.studentprep.student.StudentInternalAPI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StudentResultServiceTest {

    @Mock
    private ExamResultRepository examResultRepository;

    @Mock
    private StudentInternalAPI studentInternalAPI;

    private StudentResultService service;

    @BeforeEach
    void setUp() {
        ExamResultSummaryService summaryService = new ExamResultSummaryService();
        ExamResultSlipGenerator slipGenerator = new ExamResultSlipGenerator(
                summaryService,
                Clock.fixed(Instant.parse("2026-11-14T12:00:00Z"), ZoneOffset.UTC));
        service = new StudentResultService(
                examResultRepository,
                studentInternalAPI,
                summaryService,
                slipGenerator);
    }

    @Test
    void generateSlips_IncludeSanitizedStudentNameAndRegNumberFilename() {
        UUID studentId = UUID.randomUUID();
        UUID resultId = UUID.randomUUID();
        Student student = buildStudent(studentId, "Ada E2E O'Connor", "710920XX");
        ExamResult result = buildResult(resultId, student, 42, 60);

        when(studentInternalAPI.findByRegistrationNumberIgnoreCase("710920XX"))
                .thenReturn(Optional.of(student));
        when(examResultRepository.findById(resultId)).thenReturn(Optional.of(result));
        when(examResultRepository.findFirstByStudentIdOrderByGradedAtDesc(studentId))
                .thenReturn(Optional.of(result));

        String expectedFilename = "Ada_E2E_O_Connor_710920XX-Result-Slip.pdf";

        GeneratedResultSlip studentSlip = service.generateStudentResultSlip("710920XX", resultId);
        GeneratedResultSlip latestSlip = service.generateLatestStudentResultSlip("710920XX");
        GeneratedResultSlip adminSlip = service.generateAdminResultSlip(resultId);
        GeneratedResultSlip adminStudentSlip = service.generateAdminStudentResultSlip(studentId, resultId);

        assertThat(studentSlip.filename()).isEqualTo(expectedFilename);
        assertThat(latestSlip.filename()).isEqualTo(expectedFilename);
        assertThat(adminSlip.filename()).isEqualTo(expectedFilename);
        assertThat(adminStudentSlip.filename()).isEqualTo(expectedFilename);
        assertThat(studentSlip.content()).isNotEmpty();
    }

    @Test
    void generateStudentResultSlip_RejectsResultOwnedByAnotherStudent() {
        Student caller = buildStudent(UUID.randomUUID(), "Ada", "111111AA");
        Student owner = buildStudent(UUID.randomUUID(), "Chidi", "222222BB");
        UUID resultId = UUID.randomUUID();
        ExamResult otherResult = buildResult(resultId, owner, 50, 60);

        when(studentInternalAPI.findByRegistrationNumberIgnoreCase("111111AA"))
                .thenReturn(Optional.of(caller));
        when(examResultRepository.findById(resultId)).thenReturn(Optional.of(otherResult));

        assertThatThrownBy(() -> service.generateStudentResultSlip("111111AA", resultId))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void generateAdminStudentResultSlip_RejectsMismatchedStudentId() {
        UUID expectedStudentId = UUID.randomUUID();
        UUID actualOwnerId = UUID.randomUUID();
        UUID resultId = UUID.randomUUID();
        ExamResult result = buildResult(resultId, buildStudent(actualOwnerId, "Chidi", "222222BB"), 50, 60);

        when(examResultRepository.findById(resultId)).thenReturn(Optional.of(result));

        assertThatThrownBy(() -> service.generateAdminStudentResultSlip(expectedStudentId, resultId))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("for candidate");
    }

    @Test
    void getResultsAndLatestResult_MapTypedDtosWithSubjectScores() {
        UUID studentId = UUID.randomUUID();
        UUID resultId = UUID.randomUUID();
        Student student = buildStudent(studentId, "Ngozi Eze", "654321AB");
        ExamResult result = buildResult(resultId, student, 25, 30);
        result.setTopicBreakdown(Map.of("Biology - Ecology", new TopicStats(25, 30)));

        when(studentInternalAPI.findByRegistrationNumberIgnoreCase("654321AB"))
                .thenReturn(Optional.of(student));
        when(examResultRepository.findByStudentIdOrderByGradedAtDesc(studentId))
                .thenReturn(List.of(result));
        when(examResultRepository.findFirstByStudentIdOrderByGradedAtDesc(studentId))
                .thenReturn(Optional.of(result));

        List<StudentResultDto> history = service.getResultsForStudentRegistration("654321AB");
        LatestStudentResultDto latest = service.getLatestResultForStudentRegistration("654321AB").orElseThrow();

        assertThat(history).singleElement().satisfies(dto -> {
            assertThat(dto.studentName()).isEqualTo("Ngozi Eze");
            assertThat(dto.registrationNumber()).isEqualTo("654321AB");
            assertThat(dto.subjectScores().get("Biology")).containsExactly(25, 30);
        });
        assertThat(latest.score()).isEqualTo(25);
        assertThat(latest.total()).isEqualTo(30);
        assertThat(latest.registrationNumber()).isEqualTo("654321AB");
    }

    private static Student buildStudent(UUID id, String name, String regNumber) {
        Student student = new Student();
        student.setId(id);
        student.setName(name);
        student.setRegistrationNumber(regNumber);
        return student;
    }

    private static ExamResult buildResult(UUID id, Student student, int totalScore, int maxScore) {
        ExamResult result = new ExamResult();
        result.setId(id);
        result.setStudent(student);
        result.setTotalScore(totalScore);
        result.setMaxScore(maxScore);
        return result;
    }
}
