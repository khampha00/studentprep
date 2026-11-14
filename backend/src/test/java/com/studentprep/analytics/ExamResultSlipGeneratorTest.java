package com.studentprep.analytics;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.studentprep.student.Student;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExamResultSlipGeneratorTest {

    private static final Clock FIXED_UTC_CLOCK =
            Clock.fixed(Instant.parse("2026-11-14T12:30:45Z"), ZoneOffset.UTC);

    private final ExamResultSummaryService summaryService = new ExamResultSummaryService();
    private final ExamResultSlipGenerator slipGenerator = new ExamResultSlipGenerator(summaryService, FIXED_UTC_CLOCK);

    @Test
    void generateResultSlipPdf_RendersCandidateBioDataSubjectsAndPassStatus() throws Exception {
        UUID resultId = UUID.fromString("abcdef12-3456-7890-abcd-ef1234567890");
        Student student = new Student();
        student.setName("Ada Lovelace");
        student.setRegistrationNumber("123456JF");

        Map<String, TopicStats> topics = new LinkedHashMap<>();
        topics.put("Mathematics - Calculus", new TopicStats(18, 20));
        topics.put("Physics - Waves", new TopicStats(12, 20));

        ExamResult result = new ExamResult();
        result.setId(resultId);
        result.setStudent(student);
        result.setTotalScore(30);
        result.setMaxScore(40);
        result.setGradedAt(Instant.parse("2026-11-14T09:00:00Z"));
        result.setTopicBreakdown(topics);

        byte[] pdfBytes = slipGenerator.generateResultSlipPdf(result);

        assertThat(new String(pdfBytes, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        String text = extractAllText(pdfBytes);

        assertThat(text).contains("CBT PRACTICE EXAMINATION RESULT NOTIFICATION");
        assertThat(text).contains("Ada Lovelace");
        assertThat(text).contains("123456JF");
        assertThat(text).contains("Mathematics");
        assertThat(text).contains("Physics");
        assertThat(text).contains("30 / 40");
        assertThat(text).contains("75.0%  -  SATISFACTORY / PASS");
        assertThat(text).contains("Generated on: 2026-11-14 12:30:45");
        assertThat(text).contains("Verification Token: " + resultId);
    }

    @Test
    void generateResultSlipPdf_HandlesFailingScoreAndMissingOptionalFieldsSafely() throws Exception {
        ExamResult result = new ExamResult();
        result.setTotalScore(15);
        result.setMaxScore(50);

        byte[] pdfBytes = slipGenerator.generateResultSlipPdf(result);
        String text = extractAllText(pdfBytes);

        assertThat(new String(pdfBytes, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(text).contains("General Examination");
        assertThat(text).contains("15 / 50");
        assertThat(text).contains("30.0%  -  NEEDS IMPROVEMENT");
        assertThat(text).contains("Verification Token: N/A");
    }

    @Test
    void renderPdf_RejectsNullInput() {
        assertThatThrownBy(() -> slipGenerator.renderPdf(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> slipGenerator.generateResultSlipPdf(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String extractAllText(byte[] pdfBytes) throws Exception {
        try (PdfReader reader = new PdfReader(pdfBytes)) {
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            StringBuilder text = new StringBuilder();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                text.append(extractor.getTextFromPage(page)).append('\n');
            }
            return text.toString();
        }
    }
}
