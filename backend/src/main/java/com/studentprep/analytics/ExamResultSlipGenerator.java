package com.studentprep.analytics;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.studentprep.analytics.ExamResultSummaryService.ExamResultSlipData;
import com.studentprep.analytics.ExamResultSummaryService.SubjectScore;
import com.studentprep.common.PdfStyle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Renders printable A4 PDF examination result slips from summarized {@link ExamResultSlipData}.
 */
@Service
public class ExamResultSlipGenerator {

    private static final float PAGE_MARGIN = 36f;
    private static final float INFO_CELL_PADDING = 5f;
    private static final float SUMMARY_BORDER_WIDTH = 1.2f;

    private static final Font TOP_HEADER_FONT =
            new Font(Font.HELVETICA, 14f, Font.BOLD, PdfStyle.JAMB_GREEN);
    private static final Font SUB_HEADER_FONT =
            new Font(Font.HELVETICA, 11f, Font.BOLD, PdfStyle.SLATE_900);
    private static final Font TABLE_HEADER_FONT =
            new Font(Font.HELVETICA, 9f, Font.BOLD, Color.WHITE);
    private static final Font BOLD_CELL_FONT =
            new Font(Font.HELVETICA, 9f, Font.BOLD, PdfStyle.SLATE_900);
    private static final Font NORMAL_CELL_FONT =
            new Font(Font.HELVETICA, 9f, Font.NORMAL, PdfStyle.SLATE_700);
    private static final Font BIG_SCORE_FONT =
            new Font(Font.HELVETICA, 18f, Font.BOLD, PdfStyle.JAMB_GREEN);
    private static final Font SMALL_MUTED_FONT =
            new Font(Font.HELVETICA, 8f, Font.NORMAL, PdfStyle.SLATE_500);
    private static final Font REMARK_PASS_FONT =
            new Font(Font.HELVETICA, 12f, Font.BOLD, PdfStyle.JAMB_GREEN);
    private static final Font REMARK_FAIL_FONT =
            new Font(Font.HELVETICA, 12f, Font.BOLD, PdfStyle.RED_600);

    private static final float[] INFO_COLUMN_WIDTHS = {22f, 28f, 22f, 28f};
    private static final float[] SUBJECT_COLUMN_WIDTHS = {42f, 18f, 20f, 20f};
    private static final float[] SUMMARY_COLUMN_WIDTHS = {50f, 50f};

    private final ExamResultSummaryService summaryService;
    private final Clock clock;

    @Autowired
    public ExamResultSlipGenerator(ExamResultSummaryService summaryService) {
        this(summaryService, Clock.systemDefaultZone());
    }

    public ExamResultSlipGenerator(ExamResultSummaryService summaryService, Clock clock) {
        this.summaryService = summaryService;
        this.clock = clock;
    }

    /**
     * Generates a single-candidate printable PDF slip for a graded {@link ExamResult}.
     */
    public byte[] generateResultSlipPdf(ExamResult result) {
        ExamResultSlipData slipData = summaryService.summarize(result);
        return renderPdf(slipData);
    }

    /**
     * Renders an {@link ExamResultSlipData} view model into an A4 PDF document.
     */
    public byte[] renderPdf(ExamResultSlipData slipData) {
        if (slipData == null) {
            throw new IllegalArgumentException("Exam result slip data cannot be null");
        }

        ZoneId zoneId = clock.getZone();
        DateTimeFormatter examDateFormatter = DateTimeFormatter.ofPattern("dd MMMM yyyy, hh:mm a").withZone(zoneId);
        DateTimeFormatter generatedAtFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zoneId);

        String gradedAtText = slipData.gradedAt() != null ? examDateFormatter.format(slipData.gradedAt()) : "N/A";
        String generatedAtText = generatedAtFormatter.format(Instant.now(clock));

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, PAGE_MARGIN, PAGE_MARGIN, PAGE_MARGIN, PAGE_MARGIN);
            PdfWriter.getInstance(document, baos);
            document.open();

            addHeaderBanner(document);
            addCandidateInfoTable(document, slipData, gradedAtText);
            addSubjectScoresTable(document, slipData.subjectScores());
            addSummarySection(document, slipData);
            addFooterNotice(document, slipData.resultId(), generatedAtText);

            document.close();
            return baos.toByteArray();
        } catch (DocumentException | IOException ex) {
            throw new IllegalStateException("Failed to generate exam result PDF slip", ex);
        }
    }

    private void addHeaderBanner(Document document) throws DocumentException {
        PdfPTable headerTable = new PdfPTable(1);
        headerTable.setWidthPercentage(100f);
        headerTable.setSpacingAfter(14f);

        PdfPCell headerCell = new PdfPCell();
        headerCell.setBackgroundColor(PdfStyle.SLATE_50);
        headerCell.setBorderColor(PdfStyle.JAMB_GREEN);
        headerCell.setBorderWidth(1.5f);
        headerCell.setPadding(10f);

        headerCell.addElement(centeredParagraph("JOINT ADMISSIONS AND MATRICULATION BOARD", SUB_HEADER_FONT, 0f));
        headerCell.addElement(centeredParagraph("CBT PRACTICE EXAMINATION RESULT NOTIFICATION", TOP_HEADER_FONT, 3f));
        headerCell.addElement(centeredParagraph("OFFICIAL CANDIDATE PERFORMANCE SLIP", SMALL_MUTED_FONT, 2f));

        headerTable.addCell(headerCell);
        document.add(headerTable);
    }

    private void addCandidateInfoTable(Document document, ExamResultSlipData slipData, String gradedAtText)
            throws DocumentException {
        PdfPTable infoTable = new PdfPTable(INFO_COLUMN_WIDTHS);
        infoTable.setWidthPercentage(100f);
        infoTable.setSpacingAfter(14f);

        PdfPCell sectionHeader = new PdfPCell(
                new Phrase("CANDIDATE BIO-DATA & EXAMINATION DETAILS", TABLE_HEADER_FONT));
        sectionHeader.setColspan(4);
        sectionHeader.setBackgroundColor(PdfStyle.JAMB_GREEN);
        sectionHeader.setPadding(INFO_CELL_PADDING);
        infoTable.addCell(sectionHeader);

        addKeyValuePair(infoTable, "Candidate Name:", slipData.candidateName(), BOLD_CELL_FONT);
        addKeyValuePair(infoTable, "Registration No:", slipData.registrationNumber(), BOLD_CELL_FONT);
        addKeyValuePair(infoTable, "Examination Date:", gradedAtText, NORMAL_CELL_FONT);
        addKeyValuePair(infoTable, "Result ID:", formatResultIdPreview(slipData.resultId()), SMALL_MUTED_FONT);

        document.add(infoTable);
    }

    private void addSubjectScoresTable(Document document, List<SubjectScore> subjectScores)
            throws DocumentException {
        PdfPTable subjectTable = new PdfPTable(SUBJECT_COLUMN_WIDTHS);
        subjectTable.setWidthPercentage(100f);
        subjectTable.setSpacingAfter(14f);

        subjectTable.addCell(PdfStyle.createHeaderCell(
                "SUBJECT ENROLLED", TABLE_HEADER_FONT, Element.ALIGN_LEFT, INFO_CELL_PADDING));
        subjectTable.addCell(PdfStyle.createHeaderCell(
                "SCORE", TABLE_HEADER_FONT, Element.ALIGN_CENTER, INFO_CELL_PADDING));
        subjectTable.addCell(PdfStyle.createHeaderCell(
                "MAX MARKS", TABLE_HEADER_FONT, Element.ALIGN_CENTER, INFO_CELL_PADDING));
        subjectTable.addCell(PdfStyle.createHeaderCell(
                "PERCENTAGE", TABLE_HEADER_FONT, Element.ALIGN_CENTER, INFO_CELL_PADDING));

        for (int i = 0; i < subjectScores.size(); i++) {
            SubjectScore subject = subjectScores.get(i);
            Color rowBackground = (i % 2 == 0) ? Color.WHITE : PdfStyle.SLATE_50;

            subjectTable.addCell(PdfStyle.createBodyCell(
                    subject.subjectName(),
                    BOLD_CELL_FONT,
                    Element.ALIGN_LEFT,
                    rowBackground,
                    PdfStyle.SLATE_300,
                    INFO_CELL_PADDING));
            subjectTable.addCell(PdfStyle.createBodyCell(
                    String.valueOf(subject.score()),
                    BOLD_CELL_FONT,
                    Element.ALIGN_CENTER,
                    rowBackground,
                    PdfStyle.SLATE_300,
                    INFO_CELL_PADDING));
            subjectTable.addCell(PdfStyle.createBodyCell(
                    String.valueOf(subject.maxScore()),
                    NORMAL_CELL_FONT,
                    Element.ALIGN_CENTER,
                    rowBackground,
                    PdfStyle.SLATE_300,
                    INFO_CELL_PADDING));
            subjectTable.addCell(PdfStyle.createBodyCell(
                    String.format("%.1f%%", subject.percentage()),
                    BOLD_CELL_FONT,
                    Element.ALIGN_CENTER,
                    rowBackground,
                    PdfStyle.SLATE_300,
                    INFO_CELL_PADDING));
        }

        document.add(subjectTable);
    }

    private void addSummarySection(Document document, ExamResultSlipData slipData) throws DocumentException {
        PdfPTable summaryTable = new PdfPTable(SUMMARY_COLUMN_WIDTHS);
        summaryTable.setWidthPercentage(100f);
        summaryTable.setSpacingAfter(16f);

        PdfPCell totalScoreCell = createSummaryBoxCell();
        totalScoreCell.addElement(new Paragraph("AGGREGATE TOTAL SCORE", BOLD_CELL_FONT));
        totalScoreCell.addElement(new Paragraph(slipData.totalScore() + " / " + slipData.maxScore(), BIG_SCORE_FONT));

        PdfPCell statusCell = createSummaryBoxCell();
        statusCell.addElement(new Paragraph("OVERALL PERCENTAGE & STATUS", BOLD_CELL_FONT));
        String remark = slipData.passed() ? "SATISFACTORY / PASS" : "NEEDS IMPROVEMENT";
        Font remarkFont = slipData.passed() ? REMARK_PASS_FONT : REMARK_FAIL_FONT;
        statusCell.addElement(new Paragraph(
                String.format("%.1f%%  -  %s", slipData.overallPercentage(), remark),
                remarkFont));

        summaryTable.addCell(totalScoreCell);
        summaryTable.addCell(statusCell);
        document.add(summaryTable);
    }

    private void addFooterNotice(Document document, UUID resultId, String generatedAtText)
            throws DocumentException {
        PdfPTable footerTable = new PdfPTable(1);
        footerTable.setWidthPercentage(100f);

        PdfPCell footerCell = new PdfPCell();
        footerCell.setBackgroundColor(Color.WHITE);
        footerCell.setBorderColor(PdfStyle.SLATE_300);
        footerCell.setBorderWidth(0.8f);
        footerCell.setPadding(8f);

        footerCell.addElement(new Paragraph("IMPORTANT NOTICE / INSTRUCTIONS:", BOLD_CELL_FONT));
        footerCell.addElement(new Paragraph(
                "1. This document is a computer-generated performance notification issued by the StudentPrep CBT Mock Platform.",
                SMALL_MUTED_FONT));
        footerCell.addElement(new Paragraph(
                "2. The subject scores above reflect answers submitted under standard timed examination conditions.",
                SMALL_MUTED_FONT));
        footerCell.addElement(new Paragraph(
                "3. Keep this slip for your study records and revision reference.",
                SMALL_MUTED_FONT));

        String token = resultId != null ? resultId.toString() : "N/A";
        Paragraph timestamp = new Paragraph(
                "Generated on: " + generatedAtText + " | Verification Token: " + token,
                SMALL_MUTED_FONT);
        timestamp.setSpacingBefore(4f);
        footerCell.addElement(timestamp);

        footerTable.addCell(footerCell);
        document.add(footerTable);
    }

    private static void addKeyValuePair(PdfPTable table, String label, String value, Font valueFont) {
        table.addCell(PdfStyle.createBodyCell(
                label, BOLD_CELL_FONT, Element.ALIGN_LEFT, PdfStyle.SLATE_50, PdfStyle.SLATE_300, INFO_CELL_PADDING));
        table.addCell(PdfStyle.createBodyCell(
                value, valueFont, Element.ALIGN_LEFT, Color.WHITE, PdfStyle.SLATE_300, INFO_CELL_PADDING));
    }

    private static PdfPCell createSummaryBoxCell() {
        PdfPCell cell = new PdfPCell();
        cell.setBackgroundColor(PdfStyle.SLATE_50);
        cell.setBorderColor(PdfStyle.JAMB_GREEN);
        cell.setBorderWidth(SUMMARY_BORDER_WIDTH);
        cell.setPadding(8f);
        return cell;
    }

    private static Paragraph centeredParagraph(String text, Font font, float spacingBefore) {
        Paragraph paragraph = new Paragraph(text, font);
        paragraph.setAlignment(Element.ALIGN_CENTER);
        if (spacingBefore > 0f) {
            paragraph.setSpacingBefore(spacingBefore);
        }
        return paragraph;
    }

    private static String formatResultIdPreview(UUID resultId) {
        if (resultId == null) {
            return "N/A";
        }
        String raw = resultId.toString();
        return raw.length() > 18 ? raw.substring(0, 18) + "..." : raw;
    }
}
