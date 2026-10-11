package com.studentprep.analytics;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.studentprep.questionbank.Subject;
import com.studentprep.student.Student;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class ExamResultSlipGenerator {

    private final ExamResultRepository examResultRepository;

    public ExamResultSlipGenerator(ExamResultRepository examResultRepository) {
        this.examResultRepository = examResultRepository;
    }

    /**
     * Aggregates topic breakdown into subject scores: subjectName -> [score, maxScore]
     */
    public Map<String, int[]> computeSubjectScores(ExamResult result) {
        Map<String, int[]> subjectScores = new LinkedHashMap<>();
        
        if (result.getTopicBreakdown() != null && !result.getTopicBreakdown().isEmpty()) {
            for (Map.Entry<String, TopicStats> entry : result.getTopicBreakdown().entrySet()) {
                String key = entry.getKey();
                String subject = key;
                if (key.contains(" - ")) {
                    subject = key.substring(0, key.indexOf(" - ")).trim();
                }
                TopicStats stats = entry.getValue();
                if (stats != null) {
                    subjectScores.computeIfAbsent(subject, k -> new int[2]);
                    subjectScores.get(subject)[0] += stats.getCorrect();
                    subjectScores.get(subject)[1] += stats.getTotal();
                }
            }
        }

        // If no topic breakdown exists but student has subjects, initialize them
        if (subjectScores.isEmpty() && result.getStudent() != null && result.getStudent().getSubjects() != null) {
            for (Subject s : result.getStudent().getSubjects()) {
                subjectScores.put(s.getName(), new int[]{0, 0});
            }
        }

        // Fallback if still empty
        if (subjectScores.isEmpty()) {
            int total = result.getTotalScore() != null ? result.getTotalScore() : 0;
            int max = result.getMaxScore() != null ? result.getMaxScore() : 0;
            subjectScores.put("General Examination", new int[]{total, max});
        }

        return subjectScores;
    }

    @Transactional(readOnly = true)
    public byte[] generateResultSlipPdf(UUID resultId) {
        ExamResult result = examResultRepository.findById(resultId)
                .orElseThrow(() -> new IllegalArgumentException("Exam result not found for ID: " + resultId));

        return buildPdf(result);
    }

    @Transactional(readOnly = true)
    public byte[] generateResultSlipPdfBySessionId(UUID sessionId) {
        ExamResult result = examResultRepository.findByExamSessionId(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Exam result not found for session ID: " + sessionId));

        return buildPdf(result);
    }

    private byte[] buildPdf(ExamResult result) {
        Student student = result.getStudent();
        Map<String, int[]> subjectScores = computeSubjectScores(result);

        int totalScore = result.getTotalScore() != null ? result.getTotalScore() : 0;
        int maxScore = result.getMaxScore() != null ? result.getMaxScore() : 0;
        double overallPercentage = maxScore > 0 ? ((double) totalScore / maxScore) * 100.0 : 0.0;

        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd MMMM yyyy, hh:mm a")
                .withZone(ZoneId.systemDefault());
        String gradedAtStr = result.getGradedAt() != null ? dtf.format(result.getGradedAt()) : "N/A";

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, 36, 36, 36, 36);
            PdfWriter.getInstance(document, baos);
            document.open();

            // Palette
            Color primaryGreen = new Color(0, 135, 81);   // JAMB Green #008751
            Color darkSlate = new Color(15, 23, 42);      // Slate 900 #0f172a
            Color lightBg = new Color(248, 250, 252);     // Slate 50 #f8fafc
            Color borderGray = new Color(203, 213, 225);   // Slate 300 #cbd5e1
            Color textSlate = new Color(51, 65, 85);      // Slate 700 #334155

            // Fonts
            Font topHeaderFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14, primaryGreen);
            Font subHeaderFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, darkSlate);
            Font tableHeaderFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.WHITE);
            Font boldCellFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, darkSlate);
            Font normalCellFont = FontFactory.getFont(FontFactory.HELVETICA, 9, textSlate);
            Font bigScoreFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, primaryGreen);
            Font smallMutedFont = FontFactory.getFont(FontFactory.HELVETICA, 8, new Color(100, 116, 139));

            // 1. Header Banner Box
            PdfPTable headerTable = new PdfPTable(1);
            headerTable.setWidthPercentage(100);
            headerTable.setSpacingAfter(14f);

            PdfPCell headerCell = new PdfPCell();
            headerCell.setBackgroundColor(lightBg);
            headerCell.setBorderColor(primaryGreen);
            headerCell.setBorderWidth(1.5f);
            headerCell.setPadding(10f);

            Paragraph p1 = new Paragraph("JOINT ADMISSIONS AND MATRICULATION BOARD", subHeaderFont);
            p1.setAlignment(Element.ALIGN_CENTER);
            headerCell.addElement(p1);

            Paragraph p2 = new Paragraph("CBT PRACTICE EXAMINATION RESULT NOTIFICATION", topHeaderFont);
            p2.setAlignment(Element.ALIGN_CENTER);
            p2.setSpacingBefore(3f);
            headerCell.addElement(p2);

            Paragraph p3 = new Paragraph("OFFICIAL CANDIDATE PERFORMANCE SLIP", smallMutedFont);
            p3.setAlignment(Element.ALIGN_CENTER);
            p3.setSpacingBefore(2f);
            headerCell.addElement(p3);

            headerTable.addCell(headerCell);
            document.add(headerTable);

            // 2. Candidate Information Table
            PdfPTable infoTable = new PdfPTable(new float[]{22f, 28f, 22f, 28f});
            infoTable.setWidthPercentage(100);
            infoTable.setSpacingAfter(14f);

            // Section Header
            PdfPCell infoSectionHeader = new PdfPCell(new Phrase("CANDIDATE BIO-DATA & EXAMINATION DETAILS", tableHeaderFont));
            infoSectionHeader.setColspan(4);
            infoSectionHeader.setBackgroundColor(primaryGreen);
            infoSectionHeader.setPadding(5f);
            infoTable.addCell(infoSectionHeader);

            // Row 1: Candidate Name
            PdfPCell nameLabel = createCell("Candidate Name:", boldCellFont, lightBg, borderGray);
            PdfPCell nameVal = createCell(student != null ? student.getName() : "N/A", boldCellFont, Color.WHITE, borderGray);
            nameVal.setColspan(3);
            infoTable.addCell(nameLabel);
            infoTable.addCell(nameVal);

            // Row 2: Reg Number & Center
            PdfPCell regLabel = createCell("Registration No:", boldCellFont, lightBg, borderGray);
            PdfPCell regVal = createCell(student != null ? student.getRegistrationNumber() : "N/A", boldCellFont, Color.WHITE, borderGray);
            PdfPCell centerLabel = createCell("Exam Center:", boldCellFont, lightBg, borderGray);
            PdfPCell centerVal = createCell(student != null && student.getExamCenter() != null ? student.getExamCenter() : "CBT Center 1", normalCellFont, Color.WHITE, borderGray);
            infoTable.addCell(regLabel);
            infoTable.addCell(regVal);
            infoTable.addCell(centerLabel);
            infoTable.addCell(centerVal);

            // Row 3: Graded At & Session ID
            PdfPCell dateLabel = createCell("Examination Date:", boldCellFont, lightBg, borderGray);
            PdfPCell dateVal = createCell(gradedAtStr, normalCellFont, Color.WHITE, borderGray);
            PdfPCell sessionLabel = createCell("Result ID:", boldCellFont, lightBg, borderGray);
            PdfPCell sessionVal = createCell(result.getId().toString().substring(0, 18) + "...", smallMutedFont, Color.WHITE, borderGray);
            infoTable.addCell(dateLabel);
            infoTable.addCell(dateVal);
            infoTable.addCell(sessionLabel);
            infoTable.addCell(sessionVal);

            document.add(infoTable);

            // 3. Subject Scores Breakdown Table
            PdfPTable subjectTable = new PdfPTable(new float[]{42f, 18f, 20f, 20f});
            subjectTable.setWidthPercentage(100);
            subjectTable.setSpacingAfter(14f);

            // Header Row
            PdfPCell sh1 = createHeaderCell("SUBJECT ENROLLED", tableHeaderFont, primaryGreen);
            sh1.setHorizontalAlignment(Element.ALIGN_LEFT);
            PdfPCell sh2 = createHeaderCell("SCORE", tableHeaderFont, primaryGreen);
            PdfPCell sh3 = createHeaderCell("MAX MARKS", tableHeaderFont, primaryGreen);
            PdfPCell sh4 = createHeaderCell("PERCENTAGE", tableHeaderFont, primaryGreen);
            subjectTable.addCell(sh1);
            subjectTable.addCell(sh2);
            subjectTable.addCell(sh3);
            subjectTable.addCell(sh4);

            int rowIndex = 0;
            for (Map.Entry<String, int[]> entry : subjectScores.entrySet()) {
                Color rowBg = (rowIndex % 2 == 0) ? Color.WHITE : lightBg;
                String subjectName = entry.getKey();
                int score = entry.getValue()[0];
                int max = entry.getValue()[1];
                double pct = max > 0 ? ((double) score / max) * 100.0 : 0.0;

                PdfPCell cName = createCell(subjectName, boldCellFont, rowBg, borderGray);
                cName.setHorizontalAlignment(Element.ALIGN_LEFT);

                PdfPCell cScore = createCell(String.valueOf(score), boldCellFont, rowBg, borderGray);
                cScore.setHorizontalAlignment(Element.ALIGN_CENTER);

                PdfPCell cMax = createCell(String.valueOf(max), normalCellFont, rowBg, borderGray);
                cMax.setHorizontalAlignment(Element.ALIGN_CENTER);

                PdfPCell cPct = createCell(String.format("%.1f%%", pct), boldCellFont, rowBg, borderGray);
                cPct.setHorizontalAlignment(Element.ALIGN_CENTER);

                subjectTable.addCell(cName);
                subjectTable.addCell(cScore);
                subjectTable.addCell(cMax);
                subjectTable.addCell(cPct);
                rowIndex++;
            }

            document.add(subjectTable);

            // 4. Aggregate Total Score & Summary Box
            PdfPTable summaryTable = new PdfPTable(new float[]{50f, 50f});
            summaryTable.setWidthPercentage(100);
            summaryTable.setSpacingAfter(16f);

            PdfPCell totalScoreCell = new PdfPCell();
            totalScoreCell.setBackgroundColor(lightBg);
            totalScoreCell.setBorderColor(primaryGreen);
            totalScoreCell.setBorderWidth(1.2f);
            totalScoreCell.setPadding(8f);

            Paragraph totalLabel = new Paragraph("AGGREGATE TOTAL SCORE", boldCellFont);
            Paragraph totalVal = new Paragraph(totalScore + " / " + maxScore, bigScoreFont);
            totalScoreCell.addElement(totalLabel);
            totalScoreCell.addElement(totalVal);

            PdfPCell statusCell = new PdfPCell();
            statusCell.setBackgroundColor(lightBg);
            statusCell.setBorderColor(primaryGreen);
            statusCell.setBorderWidth(1.2f);
            statusCell.setPadding(8f);

            Paragraph pctLabel = new Paragraph("OVERALL PERCENTAGE & STATUS", boldCellFont);
            String remark = overallPercentage >= 50.0 ? "SATISFACTORY / PASS" : "NEEDS IMPROVEMENT";
            Color remarkColor = overallPercentage >= 50.0 ? primaryGreen : new Color(220, 38, 38);
            Font remarkFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, remarkColor);
            Paragraph statusVal = new Paragraph(String.format("%.1f%%  -  %s", overallPercentage, remark), remarkFont);
            statusCell.addElement(pctLabel);
            statusCell.addElement(statusVal);

            summaryTable.addCell(totalScoreCell);
            summaryTable.addCell(statusCell);
            document.add(summaryTable);

            // 5. Official Verification & Instructions Footer
            PdfPTable footerTable = new PdfPTable(1);
            footerTable.setWidthPercentage(100);

            PdfPCell footerCell = new PdfPCell();
            footerCell.setBackgroundColor(Color.WHITE);
            footerCell.setBorderColor(borderGray);
            footerCell.setBorderWidth(0.8f);
            footerCell.setPadding(8f);

            Paragraph fTitle = new Paragraph("IMPORTANT NOTICE / INSTRUCTIONS:", boldCellFont);
            footerCell.addElement(fTitle);

            Paragraph f1 = new Paragraph("1. This document is a computer-generated performance notification issued by the StudentPrep CBT Mock Platform.", smallMutedFont);
            Paragraph f2 = new Paragraph("2. The subject scores above reflect answers submitted under standard timed examination conditions.", smallMutedFont);
            Paragraph f3 = new Paragraph("3. Keep this slip for your study records and revision reference.", smallMutedFont);
            footerCell.addElement(f1);
            footerCell.addElement(f2);
            footerCell.addElement(f3);

            DateTimeFormatter genTimeDtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault());
            Paragraph fTimestamp = new Paragraph("Generated on: " + genTimeDtf.format(java.time.Instant.now()) + " | Verification Token: " + result.getId(), smallMutedFont);
            fTimestamp.setSpacingBefore(4f);
            footerCell.addElement(fTimestamp);

            footerTable.addCell(footerCell);
            document.add(footerTable);

            document.close();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate exam result PDF slip", e);
        }
    }

    private PdfPCell createCell(String text, Font font, Color bgColor, Color borderColor) {
        PdfPCell cell = new PdfPCell(new Phrase(text != null ? text : "", font));
        cell.setBackgroundColor(bgColor);
        cell.setBorderColor(borderColor);
        cell.setPadding(5f);
        return cell;
    }

    private PdfPCell createHeaderCell(String text, Font font, Color bgColor) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBackgroundColor(bgColor);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setPadding(5f);
        return cell;
    }
}
