package com.studentprep.exam;

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
import com.studentprep.common.AnswerReceiptHasher;
import com.studentprep.common.PdfStyle;
import com.studentprep.student.Student;
import com.studentprep.student.StudentInternalAPI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Reconstructs a candidate's frozen examination paper, submitted answers, SHA-256 cryptographic
 * answer receipt, and sync/submission timeline provenance for administrative and supervisory
 * dispute resolution, and renders printable A4 audit script PDFs.
 */
@Service
public class ExamScriptAuditService {

    private static final float PAGE_MARGIN = 32f;
    private static final float CELL_PADDING = 5f;

    private static final Font TOP_HEADER_FONT =
            new Font(Font.HELVETICA, 13f, Font.BOLD, PdfStyle.JAMB_GREEN);
    private static final Font SUB_HEADER_FONT =
            new Font(Font.HELVETICA, 10f, Font.BOLD, PdfStyle.SLATE_900);
    private static final Font TABLE_HEADER_FONT =
            new Font(Font.HELVETICA, 8.5f, Font.BOLD, Color.WHITE);
    private static final Font BOLD_CELL_FONT =
            new Font(Font.HELVETICA, 8.5f, Font.BOLD, PdfStyle.SLATE_900);
    private static final Font NORMAL_CELL_FONT =
            new Font(Font.HELVETICA, 8.5f, Font.NORMAL, PdfStyle.SLATE_700);
    private static final Font SMALL_MUTED_FONT =
            new Font(Font.HELVETICA, 7.5f, Font.NORMAL, PdfStyle.SLATE_500);
    private static final Font CORRECT_FONT =
            new Font(Font.HELVETICA, 8f, Font.BOLD, PdfStyle.JAMB_GREEN);
    private static final Font INCORRECT_FONT =
            new Font(Font.HELVETICA, 8f, Font.BOLD, PdfStyle.RED_600);
    private static final Font UNANSWERED_FONT =
            new Font(Font.HELVETICA, 8f, Font.BOLD, PdfStyle.SLATE_500);

    private static final float[] INFO_COLUMN_WIDTHS = {22f, 28f, 22f, 28f};
    private static final float[] SUBJECT_SUMMARY_WIDTHS = {32f, 11f, 12f, 11f, 11f, 12f, 11f};
    private static final float[] QUESTION_AUDIT_WIDTHS = {6f, 14f, 50f, 10f, 10f, 10f};

    private final ExamSessionRepository sessionRepository;
    private final StudentInternalAPI studentInternalAPI;
    private final ExamPayloadGenerator examPayloadGenerator;
    private final Clock clock;

    @Autowired
    public ExamScriptAuditService(
            ExamSessionRepository sessionRepository,
            StudentInternalAPI studentInternalAPI,
            ExamPayloadGenerator examPayloadGenerator
    ) {
        this(sessionRepository, studentInternalAPI, examPayloadGenerator, Clock.systemDefaultZone());
    }

    public ExamScriptAuditService(
            ExamSessionRepository sessionRepository,
            StudentInternalAPI studentInternalAPI,
            ExamPayloadGenerator examPayloadGenerator,
            Clock clock
    ) {
        this.sessionRepository = sessionRepository;
        this.studentInternalAPI = studentInternalAPI;
        this.examPayloadGenerator = examPayloadGenerator;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ExamScriptAuditDto getSessionAudit(UUID sessionId) {
        if (sessionId == null) {
            throw new IllegalArgumentException("Session ID is required");
        }

        ExamSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Exam session not found: " + sessionId));

        UUID studentId = session.getUserId();
        Student student = studentId != null
                ? studentInternalAPI.findById(studentId).orElse(null)
                : null;

        String studentName = student != null && student.getName() != null
                ? student.getName()
                : "Unknown Candidate";
        String registrationNumber = student != null && student.getRegistrationNumber() != null
                ? student.getRegistrationNumber()
                : "UNKNOWN";

        Map<String, Object> snapshot = session.getSnapshotPayload();
        if (snapshot == null || snapshot.isEmpty()) {
            snapshot = examPayloadGenerator.buildSessionSnapshot(studentId, session.getShuffleSeed());
        }

        Map<String, String> contexts = extractContexts(snapshot);
        Map<String, Object> statePayload = session.getStatePayload();
        Map<String, String> answers = extractAnswers(statePayload);
        Set<String> flaggedIds = extractFlaggedIds(statePayload);
        int tabSwitchCount = extractTabSwitchCount(statePayload);
        Instant lastClientSyncAt = extractLastClientSyncAt(statePayload, session.getUpdatedAt());
        String submissionTrigger = resolveSubmissionTrigger(session.getStatus(), statePayload);

        String answerSha256 = AnswerReceiptHasher.computeSha256(session.getId(), answers);
        String answerReceiptCode = AnswerReceiptHasher.formatReceiptCode(answerSha256);

        List<QuestionAuditItem> auditItems = new ArrayList<>();
        Map<String, SubjectAccumulator> subjectAccumulators = new LinkedHashMap<>();
        Map<String, Integer> perSubjectQuestionIndex = new HashMap<>();

        int totalQuestions = 0;
        int answeredCount = 0;
        int correctCount = 0;
        int incorrectCount = 0;
        int unansweredCount = 0;

        Object questionsObj = snapshot != null ? snapshot.get("questions") : null;
        if (questionsObj instanceof List<?> questionList) {
            for (Object item : questionList) {
                if (!(item instanceof Map<?, ?> qMap)) {
                    continue;
                }

                totalQuestions++;
                String questionId = extractString(qMap.get("id"));
                String subjectId = extractSubjectId(qMap);
                String subjectName = extractSubjectName(qMap);
                String topic = extractTopic(qMap);
                String sharedContext = extractSharedContext(qMap, contexts);
                String prompt = extractPrompt(qMap);
                List<Map<String, Object>> assets = extractAssets(qMap);
                Map<String, String> options = extractOptions(qMap);
                String correctOption = normalizeOptionKey(extractCorrectOption(qMap));
                String selectedOption = questionId != null ? normalizeOptionKey(answers.get(questionId)) : null;
                boolean flagged = questionId != null && flaggedIds.contains(questionId);

                String outcome;
                if (selectedOption == null) {
                    outcome = "UNANSWERED";
                    unansweredCount++;
                } else if (correctOption != null && correctOption.equalsIgnoreCase(selectedOption)) {
                    outcome = "CORRECT";
                    answeredCount++;
                    correctCount++;
                } else {
                    outcome = "INCORRECT";
                    answeredCount++;
                    incorrectCount++;
                }

                String subjectKey = subjectName.toUpperCase(Locale.ROOT);
                int subjectQuestionNumber = perSubjectQuestionIndex.merge(subjectKey, 1, Integer::sum);

                SubjectAccumulator acc = subjectAccumulators.computeIfAbsent(
                        subjectKey,
                        k -> new SubjectAccumulator(subjectId, subjectName)
                );
                acc.totalQuestions++;
                if ("CORRECT".equals(outcome)) {
                    acc.answeredCount++;
                    acc.correctCount++;
                } else if ("INCORRECT".equals(outcome)) {
                    acc.answeredCount++;
                    acc.incorrectCount++;
                } else {
                    acc.unansweredCount++;
                }

                auditItems.add(new QuestionAuditItem(
                        totalQuestions,
                        subjectQuestionNumber,
                        questionId != null ? questionId : "q-" + totalQuestions,
                        subjectId,
                        subjectName,
                        topic,
                        sharedContext,
                        prompt,
                        assets,
                        options,
                        selectedOption,
                        correctOption,
                        outcome,
                        flagged
                ));
            }
        }

        List<SubjectAuditSummary> subjectSummaries = new ArrayList<>();
        for (SubjectAccumulator acc : subjectAccumulators.values()) {
            double subjPct = acc.totalQuestions > 0
                    ? (acc.correctCount * 100.0) / acc.totalQuestions
                    : 0.0;
            subjectSummaries.add(new SubjectAuditSummary(
                    acc.subjectId,
                    acc.subjectName,
                    acc.totalQuestions,
                    acc.answeredCount,
                    acc.correctCount,
                    acc.incorrectCount,
                    acc.unansweredCount,
                    subjPct
            ));
        }

        double overallPct = totalQuestions > 0
                ? (correctCount * 100.0) / totalQuestions
                : 0.0;

        return new ExamScriptAuditDto(
                session.getId(),
                studentId,
                studentName,
                registrationNumber,
                session.getStatus() != null ? session.getStatus().name() : "UNKNOWN",
                submissionTrigger,
                session.getStartTime(),
                session.getExpectedEndTime(),
                session.getEndTime(),
                lastClientSyncAt,
                tabSwitchCount,
                answerReceiptCode,
                answerSha256,
                totalQuestions,
                answeredCount,
                correctCount,
                incorrectCount,
                unansweredCount,
                overallPct,
                subjectSummaries,
                auditItems
        );
    }

    @Transactional(readOnly = true)
    public GeneratedAuditPdf generateSessionAuditPdf(UUID sessionId) {
        ExamScriptAuditDto audit = getSessionAudit(sessionId);
        byte[] pdfBytes = renderAuditPdf(audit);
        String filename = buildAuditFilename(audit.studentName(), audit.registrationNumber(), audit.sessionId());
        return new GeneratedAuditPdf(pdfBytes, filename);
    }

    public byte[] renderAuditPdf(ExamScriptAuditDto audit) {
        if (audit == null) {
            throw new IllegalArgumentException("Audit data cannot be null");
        }

        ZoneId zoneId = clock.getZone();
        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm:ss a").withZone(zoneId);
        DateTimeFormatter generatedFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zoneId);

        String startText = audit.startTime() != null ? dateFormatter.format(audit.startTime()) : "N/A";
        String endText = audit.endTime() != null ? dateFormatter.format(audit.endTime()) : "In Progress / Not Recorded";
        String lastSyncText = audit.lastClientSyncAt() != null ? dateFormatter.format(audit.lastClientSyncAt()) : "N/A";
        String generatedAtText = generatedFormatter.format(Instant.now(clock));

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, PAGE_MARGIN, PAGE_MARGIN, PAGE_MARGIN, PAGE_MARGIN);
            PdfWriter.getInstance(document, baos);
            document.open();

            addHeaderBanner(document);
            addSessionMetadataTable(document, audit, startText, endText, lastSyncText);
            addSubjectSummaryTable(document, audit.subjectSummaries());
            addQuestionAuditTable(document, audit.questions());
            addFooterVerificationBox(document, audit, generatedAtText);

            document.close();
            return baos.toByteArray();
        } catch (DocumentException | IOException ex) {
            throw new IllegalStateException("Failed to generate exam script audit PDF", ex);
        }
    }

    private void addHeaderBanner(Document document) throws DocumentException {
        PdfPTable headerTable = new PdfPTable(1);
        headerTable.setWidthPercentage(100f);
        headerTable.setSpacingAfter(12f);

        PdfPCell headerCell = new PdfPCell();
        headerCell.setBackgroundColor(PdfStyle.SLATE_50);
        headerCell.setBorderColor(PdfStyle.JAMB_GREEN);
        headerCell.setBorderWidth(1.5f);
        headerCell.setPadding(9f);

        headerCell.addElement(centeredParagraph("JOINT ADMISSIONS AND MATRICULATION BOARD", SUB_HEADER_FONT, 0f));
        headerCell.addElement(centeredParagraph(
                "OFFICIAL EXAMINATION SCRIPT AUDIT & VERIFICATION REPORT", TOP_HEADER_FONT, 2f));
        headerCell.addElement(centeredParagraph(
                "ADMINISTRATIVE & SUPERVISORY QUESTION-BY-QUESTION ANSWER RECORD", SMALL_MUTED_FONT, 2f));

        headerTable.addCell(headerCell);
        document.add(headerTable);
    }

    private void addSessionMetadataTable(
            Document document,
            ExamScriptAuditDto audit,
            String startText,
            String endText,
            String lastSyncText
    ) throws DocumentException {
        PdfPTable infoTable = new PdfPTable(INFO_COLUMN_WIDTHS);
        infoTable.setWidthPercentage(100f);
        infoTable.setSpacingAfter(12f);

        PdfPCell sectionHeader = new PdfPCell(
                new Phrase("CANDIDATE, PROVENANCE & CRYPTOGRAPHIC RECEIPT METADATA", TABLE_HEADER_FONT));
        sectionHeader.setColspan(4);
        sectionHeader.setBackgroundColor(PdfStyle.JAMB_GREEN);
        sectionHeader.setPadding(CELL_PADDING);
        infoTable.addCell(sectionHeader);

        addKeyValuePair(infoTable, "Candidate Name:", audit.studentName(), BOLD_CELL_FONT);
        addKeyValuePair(infoTable, "Registration No:", audit.registrationNumber(), BOLD_CELL_FONT);
        addKeyValuePair(infoTable, "Session Status:", audit.status(), BOLD_CELL_FONT);
        addKeyValuePair(infoTable, "Submission Trigger:", audit.submissionTrigger(), BOLD_CELL_FONT);
        addKeyValuePair(infoTable, "Started At:", startText, NORMAL_CELL_FONT);
        addKeyValuePair(infoTable, "Submitted / Ended:", endText, NORMAL_CELL_FONT);
        addKeyValuePair(infoTable, "Last Client Sync:", lastSyncText, NORMAL_CELL_FONT);
        addKeyValuePair(infoTable, "Tab Switch Strikes:", String.valueOf(audit.tabSwitchCount()), BOLD_CELL_FONT);
        addKeyValuePair(infoTable, "Answer Receipt Code:", audit.answerReceiptCode(), CORRECT_FONT);
        addKeyValuePair(infoTable, "Aggregate Score:",
                String.format("%d / %d (%.1f%%)", audit.correctCount(), audit.totalQuestions(), audit.percentage()),
                CORRECT_FONT);

        PdfPCell breakdownLabel = PdfStyle.createBodyCell(
                "Answer Counts:", BOLD_CELL_FONT, Element.ALIGN_LEFT, PdfStyle.SLATE_50, PdfStyle.SLATE_300, CELL_PADDING);
        infoTable.addCell(breakdownLabel);

        PdfPCell breakdownValue = PdfStyle.createBodyCell(
                String.format("Total: %d | Answered: %d | Correct: %d | Wrong: %d | Unanswered: %d",
                        audit.totalQuestions(), audit.answeredCount(), audit.correctCount(), audit.incorrectCount(), audit.unansweredCount()),
                NORMAL_CELL_FONT, Element.ALIGN_LEFT, Color.WHITE, PdfStyle.SLATE_300, CELL_PADDING);
        breakdownValue.setColspan(3);
        infoTable.addCell(breakdownValue);

        document.add(infoTable);
    }

    private void addSubjectSummaryTable(Document document, List<SubjectAuditSummary> summaries)
            throws DocumentException {
        if (summaries == null || summaries.isEmpty()) {
            return;
        }

        PdfPTable table = new PdfPTable(SUBJECT_SUMMARY_WIDTHS);
        table.setWidthPercentage(100f);
        table.setSpacingAfter(12f);

        table.addCell(PdfStyle.createHeaderCell("SUBJECT", TABLE_HEADER_FONT, Element.ALIGN_LEFT, CELL_PADDING));
        table.addCell(PdfStyle.createHeaderCell("TOTAL", TABLE_HEADER_FONT, Element.ALIGN_CENTER, CELL_PADDING));
        table.addCell(PdfStyle.createHeaderCell("ANSWERED", TABLE_HEADER_FONT, Element.ALIGN_CENTER, CELL_PADDING));
        table.addCell(PdfStyle.createHeaderCell("CORRECT", TABLE_HEADER_FONT, Element.ALIGN_CENTER, CELL_PADDING));
        table.addCell(PdfStyle.createHeaderCell("WRONG", TABLE_HEADER_FONT, Element.ALIGN_CENTER, CELL_PADDING));
        table.addCell(PdfStyle.createHeaderCell("BLANK", TABLE_HEADER_FONT, Element.ALIGN_CENTER, CELL_PADDING));
        table.addCell(PdfStyle.createHeaderCell("SCORE %", TABLE_HEADER_FONT, Element.ALIGN_CENTER, CELL_PADDING));

        for (int i = 0; i < summaries.size(); i++) {
            SubjectAuditSummary s = summaries.get(i);
            Color bg = (i % 2 == 0) ? Color.WHITE : PdfStyle.SLATE_50;

            table.addCell(PdfStyle.createBodyCell(
                    s.subjectName(), BOLD_CELL_FONT, Element.ALIGN_LEFT, bg, PdfStyle.SLATE_300, CELL_PADDING));
            table.addCell(PdfStyle.createBodyCell(
                    String.valueOf(s.totalQuestions()), NORMAL_CELL_FONT, Element.ALIGN_CENTER, bg, PdfStyle.SLATE_300, CELL_PADDING));
            table.addCell(PdfStyle.createBodyCell(
                    String.valueOf(s.answeredCount()), NORMAL_CELL_FONT, Element.ALIGN_CENTER, bg, PdfStyle.SLATE_300, CELL_PADDING));
            table.addCell(PdfStyle.createBodyCell(
                    String.valueOf(s.correctCount()), CORRECT_FONT, Element.ALIGN_CENTER, bg, PdfStyle.SLATE_300, CELL_PADDING));
            table.addCell(PdfStyle.createBodyCell(
                    String.valueOf(s.incorrectCount()), INCORRECT_FONT, Element.ALIGN_CENTER, bg, PdfStyle.SLATE_300, CELL_PADDING));
            table.addCell(PdfStyle.createBodyCell(
                    String.valueOf(s.unansweredCount()), UNANSWERED_FONT, Element.ALIGN_CENTER, bg, PdfStyle.SLATE_300, CELL_PADDING));
            table.addCell(PdfStyle.createBodyCell(
                    String.format("%.1f%%", s.percentage()), BOLD_CELL_FONT, Element.ALIGN_CENTER, bg, PdfStyle.SLATE_300, CELL_PADDING));
        }

        document.add(table);
    }

    private void addQuestionAuditTable(Document document, List<QuestionAuditItem> questions)
            throws DocumentException {
        PdfPTable table = new PdfPTable(QUESTION_AUDIT_WIDTHS);
        table.setWidthPercentage(100f);
        table.setHeaderRows(1);
        table.setSpacingAfter(12f);

        table.addCell(PdfStyle.createHeaderCell("#", TABLE_HEADER_FONT, Element.ALIGN_CENTER, CELL_PADDING));
        table.addCell(PdfStyle.createHeaderCell("SUBJECT", TABLE_HEADER_FONT, Element.ALIGN_LEFT, CELL_PADDING));
        table.addCell(PdfStyle.createHeaderCell("QUESTION & OPTIONS SNAPSHOT", TABLE_HEADER_FONT, Element.ALIGN_LEFT, CELL_PADDING));
        table.addCell(PdfStyle.createHeaderCell("PICKED", TABLE_HEADER_FONT, Element.ALIGN_CENTER, CELL_PADDING));
        table.addCell(PdfStyle.createHeaderCell("CORRECT", TABLE_HEADER_FONT, Element.ALIGN_CENTER, CELL_PADDING));
        table.addCell(PdfStyle.createHeaderCell("OUTCOME", TABLE_HEADER_FONT, Element.ALIGN_CENTER, CELL_PADDING));

        if (questions == null || questions.isEmpty()) {
            PdfPCell emptyCell = PdfStyle.createBodyCell(
                    "No snapshot questions recorded for this session.",
                    NORMAL_CELL_FONT,
                    Element.ALIGN_CENTER,
                    Color.WHITE,
                    PdfStyle.SLATE_300,
                    8f);
            emptyCell.setColspan(6);
            table.addCell(emptyCell);
            document.add(table);
            return;
        }

        for (int i = 0; i < questions.size(); i++) {
            QuestionAuditItem q = questions.get(i);
            Color bg = (i % 2 == 0) ? Color.WHITE : PdfStyle.SLATE_50;

            table.addCell(PdfStyle.createBodyCell(
                    String.valueOf(q.questionNumber()),
                    BOLD_CELL_FONT,
                    Element.ALIGN_CENTER,
                    bg,
                    PdfStyle.SLATE_300,
                    CELL_PADDING));

            String subjectLabel = q.subjectName() + " (Q" + q.subjectQuestionNumber() + ")";
            table.addCell(PdfStyle.createBodyCell(
                    subjectLabel,
                    BOLD_CELL_FONT,
                    Element.ALIGN_LEFT,
                    bg,
                    PdfStyle.SLATE_300,
                    CELL_PADDING));

            PdfPCell questionCell = new PdfPCell();
            questionCell.setBackgroundColor(bg);
            questionCell.setBorderColor(PdfStyle.SLATE_300);
            questionCell.setPadding(CELL_PADDING);

            Paragraph stemPara = new Paragraph(formatMathForPdf(q.prompt()), NORMAL_CELL_FONT);
            stemPara.setSpacingAfter(3f);
            questionCell.addElement(stemPara);

            if (q.options() != null && !q.options().isEmpty()) {
                for (Map.Entry<String, String> opt : q.options().entrySet()) {
                    String optKey = opt.getKey();
                    String optText = formatMathForPdf(opt.getValue());
                    boolean isSelected = optKey.equalsIgnoreCase(q.selectedOption());
                    boolean isCorrect = optKey.equalsIgnoreCase(q.correctOption());

                    Font optFont = SMALL_MUTED_FONT;
                    String suffix = "";
                    if (isCorrect && isSelected) {
                        optFont = CORRECT_FONT;
                        suffix = " [CANDIDATE PICK & CORRECT]";
                    } else if (isCorrect) {
                        optFont = CORRECT_FONT;
                        suffix = " [CORRECT KEY]";
                    } else if (isSelected) {
                        optFont = INCORRECT_FONT;
                        suffix = " [CANDIDATE PICK]";
                    }

                    Paragraph optPara = new Paragraph(optKey + ") " + optText + suffix, optFont);
                    questionCell.addElement(optPara);
                }
            }
            table.addCell(questionCell);

            String pickedText = q.selectedOption() != null ? q.selectedOption() : "BLANK";
            if (q.flagged()) {
                pickedText += " *";
            }
            Font pickedFont = "CORRECT".equals(q.outcome())
                    ? CORRECT_FONT
                    : ("INCORRECT".equals(q.outcome()) ? INCORRECT_FONT : UNANSWERED_FONT);
            table.addCell(PdfStyle.createBodyCell(
                    pickedText, pickedFont, Element.ALIGN_CENTER, bg, PdfStyle.SLATE_300, CELL_PADDING));

            table.addCell(PdfStyle.createBodyCell(
                    q.correctOption() != null ? q.correctOption() : "N/A",
                    CORRECT_FONT,
                    Element.ALIGN_CENTER,
                    bg,
                    PdfStyle.SLATE_300,
                    CELL_PADDING));

            Font outcomeFont = "CORRECT".equals(q.outcome())
                    ? CORRECT_FONT
                    : ("INCORRECT".equals(q.outcome()) ? INCORRECT_FONT : UNANSWERED_FONT);
            table.addCell(PdfStyle.createBodyCell(
                    q.outcome(), outcomeFont, Element.ALIGN_CENTER, bg, PdfStyle.SLATE_300, CELL_PADDING));
        }

        document.add(table);
    }

    private void addFooterVerificationBox(Document document, ExamScriptAuditDto audit, String generatedAtText)
            throws DocumentException {
        PdfPTable footerTable = new PdfPTable(1);
        footerTable.setWidthPercentage(100f);

        PdfPCell footerCell = new PdfPCell();
        footerCell.setBackgroundColor(Color.WHITE);
        footerCell.setBorderColor(PdfStyle.SLATE_300);
        footerCell.setBorderWidth(0.8f);
        footerCell.setPadding(7f);

        footerCell.addElement(new Paragraph("CRYPTOGRAPHIC & SNAPSHOT INTEGRITY CERTIFICATION:", BOLD_CELL_FONT));
        footerCell.addElement(new Paragraph(
                "1. Reconstructed directly from immutable exam_sessions.snapshot_payload and state_payload.",
                SMALL_MUTED_FONT));
        footerCell.addElement(new Paragraph(
                "2. Answer Receipt Code: " + audit.answerReceiptCode() + " | SHA-256: " + audit.answerSha256(),
                SMALL_MUTED_FONT));
        footerCell.addElement(new Paragraph(
                "Generated on: " + generatedAtText + " | Session ID: " + (audit.sessionId() != null ? audit.sessionId() : "N/A"),
                SMALL_MUTED_FONT));

        footerTable.addCell(footerCell);
        document.add(footerTable);
    }

    private static void addKeyValuePair(PdfPTable table, String label, String value, Font valueFont) {
        table.addCell(PdfStyle.createBodyCell(
                label, BOLD_CELL_FONT, Element.ALIGN_LEFT, PdfStyle.SLATE_50, PdfStyle.SLATE_300, CELL_PADDING));
        table.addCell(PdfStyle.createBodyCell(
                value, valueFont, Element.ALIGN_LEFT, Color.WHITE, PdfStyle.SLATE_300, CELL_PADDING));
    }

    private static Paragraph centeredParagraph(String text, Font font, float spacingBefore) {
        Paragraph paragraph = new Paragraph(text, font);
        paragraph.setAlignment(Element.ALIGN_CENTER);
        if (spacingBefore > 0f) {
            paragraph.setSpacingBefore(spacingBefore);
        }
        return paragraph;
    }

    public static String formatMathForPdf(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String cleaned = raw
                .replace("$$", "")
                .replace("$", "")
                .replace("\\(", "")
                .replace("\\)", "")
                .replace("^\\circ", " deg")
                .replace("\\circ", " deg")
                .replace("\\times", " x ")
                .replace("\\div", " / ")
                .replace("\\pm", "+/-")
                .replace("\\le", "<=")
                .replace("\\leq", "<=")
                .replace("\\ge", ">=")
                .replace("\\geq", ">=")
                .replace("\\neq", "!=")
                .replace("\\approx", "~=")
                .replace("\\infty", "infinity")
                .replace("\\pi", "pi")
                .replace("\\theta", "theta")
                .replace("\\alpha", "alpha")
                .replace("\\beta", "beta")
                .replace("\\gamma", "gamma")
                .replace("\\lambda", "lambda")
                .replace("\\mu", "mu")
                .replace("\\Omega", "Ohm")
                .replace("\\rightarrow", "->")
                .replace("\\leftarrow", "<-");

        cleaned = cleaned.replaceAll("\\\\frac\\{([^}]*)\\}\\{([^}]*)\\}", "($1)/($2)");
        cleaned = cleaned.replaceAll("\\\\sqrt\\{([^}]*)\\}", "sqrt($1)");
        cleaned = cleaned.replaceAll("\\\\text\\{([^}]*)\\}", "$1");
        cleaned = cleaned.replaceAll("\\\\mathrm\\{([^}]*)\\}", "$1");
        return cleaned.trim();
    }

    private static Instant extractLastClientSyncAt(Map<String, Object> statePayload, Instant fallbackUpdatedAt) {
        if (statePayload != null) {
            Object rawLastUpdated = statePayload.get("lastUpdated");
            if (rawLastUpdated instanceof Number num) {
                long epochMs = num.longValue();
                if (epochMs > 0) {
                    return Instant.ofEpochMilli(epochMs);
                }
            } else if (rawLastUpdated instanceof String str && !str.isBlank()) {
                try {
                    long epochMs = Long.parseLong(str.trim());
                    if (epochMs > 0) {
                        return Instant.ofEpochMilli(epochMs);
                    }
                } catch (NumberFormatException ignored) {
                    try {
                        return Instant.parse(str.trim());
                    } catch (Exception ignoredIso) {
                        // Fall through to session updatedAt
                    }
                }
            }
        }
        return fallbackUpdatedAt;
    }

    private static String resolveSubmissionTrigger(ExamSessionStatus status, Map<String, Object> statePayload) {
        String reason = statePayload != null ? extractString(statePayload.get("reason")) : null;
        if (status == ExamSessionStatus.FLAGGED_TAB_SWITCH
                || "TAB_SWITCH_EXCEEDED".equalsIgnoreCase(reason)) {
            return "MALPRACTICE_AUTO_SUBMIT";
        }
        if (status == ExamSessionStatus.LATE_SUBMISSION_FLAGGED) {
            return "LATE_OFFLINE_SYNC";
        }
        if (status == ExamSessionStatus.TIME_EXPIRED) {
            if ("TIME_EXPIRED".equalsIgnoreCase(reason)) {
                return "TIMER_AUTO_SUBMIT";
            }
            return "SERVER_EXPIRY_SWEEP";
        }
        if (status == ExamSessionStatus.IN_PROGRESS) {
            return "IN_PROGRESS";
        }
        if ("TIME_EXPIRED".equalsIgnoreCase(reason)) {
            return "TIMER_AUTO_SUBMIT";
        }
        return "MANUAL_SUBMIT";
    }

    private static String buildAuditFilename(String studentName, String registrationNumber, UUID sessionId) {
        String safeName = sanitizeSegment(studentName, "Candidate");
        String safeReg = sanitizeSegment(registrationNumber, sessionId != null ? sessionId.toString() : "Session");
        return safeName + "_" + safeReg + "-Exam-Script-Audit.pdf";
    }

    private static String sanitizeSegment(String input, String fallback) {
        if (input == null || input.isBlank()) {
            return fallback;
        }
        String cleaned = input.trim().replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return cleaned.isEmpty() ? fallback : cleaned;
    }

    private static Map<String, String> extractContexts(Map<String, Object> snapshot) {
        if (snapshot == null) {
            return Collections.emptyMap();
        }
        Object ctxObj = snapshot.get("contexts");
        if (!(ctxObj instanceof Map<?, ?> ctxMap)) {
            return Collections.emptyMap();
        }
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<?, ?> entry : ctxMap.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                result.put(entry.getKey().toString(), entry.getValue().toString());
            }
        }
        return result;
    }

    private static Map<String, String> extractAnswers(Map<String, Object> statePayload) {
        if (statePayload == null) {
            return Collections.emptyMap();
        }
        Object ansObj = statePayload.get("answers");
        if (!(ansObj instanceof Map<?, ?> ansMap)) {
            return Collections.emptyMap();
        }
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<?, ?> entry : ansMap.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                result.put(entry.getKey().toString(), entry.getValue().toString());
            }
        }
        return result;
    }

    private static Set<String> extractFlaggedIds(Map<String, Object> statePayload) {
        if (statePayload == null) {
            return Collections.emptySet();
        }
        Object flaggedObj = statePayload.get("flagged");
        Set<String> result = new HashSet<>();
        if (flaggedObj instanceof Map<?, ?> flaggedMap) {
            for (Map.Entry<?, ?> entry : flaggedMap.entrySet()) {
                if (entry.getKey() != null && Boolean.TRUE.equals(entry.getValue())) {
                    result.add(entry.getKey().toString());
                }
            }
        } else if (flaggedObj instanceof Collection<?> flaggedCol) {
            for (Object item : flaggedCol) {
                if (item != null) {
                    result.add(item.toString());
                }
            }
        }
        return result;
    }

    private static int extractTabSwitchCount(Map<String, Object> statePayload) {
        if (statePayload != null && statePayload.get("tabSwitchCount") instanceof Number num) {
            return num.intValue();
        }
        return 0;
    }

    private static String extractString(Object obj) {
        if (obj == null) {
            return null;
        }
        String str = obj.toString().trim();
        return str.isEmpty() ? null : str;
    }

    private static String extractSubjectId(Map<?, ?> qMap) {
        Object subjectObj = qMap.get("subject");
        if (subjectObj instanceof Map<?, ?> sm && sm.get("id") != null) {
            return sm.get("id").toString();
        }
        Object direct = qMap.get("subjectId");
        return direct != null ? direct.toString() : "unknown";
    }

    private static String extractSubjectName(Map<?, ?> qMap) {
        Object subjectObj = qMap.get("subject");
        if (subjectObj instanceof Map<?, ?> sm) {
            String name = extractString(sm.get("name"));
            if (name != null) {
                return name;
            }
        }
        String direct = extractString(qMap.get("subjectName"));
        return direct != null ? direct : "General";
    }

    private static String extractTopic(Map<?, ?> qMap) {
        String topic = extractString(qMap.get("topic"));
        return (topic != null && !"unknown".equalsIgnoreCase(topic)) ? topic : "General Assessment";
    }

    private static String extractSharedContext(Map<?, ?> qMap, Map<String, String> contexts) {
        String contextId = extractString(qMap.get("contextId"));
        if (contextId != null && contexts.containsKey(contextId)) {
            return contexts.get(contextId);
        }
        Object contentObj = qMap.get("content");
        if (contentObj instanceof Map<?, ?> cm) {
            String shared = extractString(cm.get("shared_context"));
            if (shared != null) {
                return shared;
            }
        }
        return null;
    }

    private static String extractPrompt(Map<?, ?> qMap) {
        Object contentObj = qMap.get("content");
        if (contentObj instanceof Map<?, ?> cm) {
            String text = extractString(cm.get("text"));
            if (text != null) {
                return text;
            }
            String prompt = extractString(cm.get("prompt"));
            if (prompt != null) {
                return prompt;
            }
        }
        String fallback = extractString(qMap.get("text"));
        return fallback != null ? fallback : "";
    }

    private static List<Map<String, Object>> extractAssets(Map<?, ?> qMap) {
        Object contentObj = qMap.get("content");
        if (contentObj instanceof Map<?, ?> cm && cm.get("assets") instanceof List<?> rawList) {
            List<Map<String, Object>> assets = new ArrayList<>();
            for (Object item : rawList) {
                if (item instanceof Map<?, ?> assetMap) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : assetMap.entrySet()) {
                        if (e.getKey() != null) {
                            copy.put(e.getKey().toString(), e.getValue());
                        }
                    }
                    assets.add(copy);
                }
            }
            return assets;
        }
        return Collections.emptyList();
    }

    private static Map<String, String> extractOptions(Map<?, ?> qMap) {
        Object contentObj = qMap.get("content");
        Map<String, String> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (contentObj instanceof Map<?, ?> cm) {
            Object optsObj = cm.get("options");
            if (optsObj instanceof Map<?, ?> optsMap) {
                for (Map.Entry<?, ?> entry : optsMap.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        sorted.put(entry.getKey().toString().trim().toUpperCase(Locale.ROOT), entry.getValue().toString());
                    }
                }
            } else if (optsObj instanceof List<?> optsList) {
                char letter = 'A';
                for (Object item : optsList) {
                    if (item instanceof Map<?, ?> optItem) {
                        String key = extractString(optItem.get("id"));
                        if (key == null) {
                            key = extractString(optItem.get("key"));
                        }
                        if (key == null) {
                            key = String.valueOf(letter);
                        }
                        String text = extractString(optItem.get("text"));
                        sorted.put(key.toUpperCase(Locale.ROOT), text != null ? text : "");
                    } else if (item != null) {
                        sorted.put(String.valueOf(letter), item.toString());
                    }
                    letter++;
                }
            }
        }
        return new LinkedHashMap<>(sorted);
    }

    private static String extractCorrectOption(Map<?, ?> qMap) {
        Object contentObj = qMap.get("content");
        if (contentObj instanceof Map<?, ?> cm) {
            String opt = extractString(cm.get("correctOption"));
            if (opt != null) {
                return opt;
            }
        }
        return extractString(qMap.get("correctOption"));
    }

    private static String normalizeOptionKey(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim().toUpperCase(Locale.ROOT);
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static final class SubjectAccumulator {
        private final String subjectId;
        private final String subjectName;
        private int totalQuestions;
        private int answeredCount;
        private int correctCount;
        private int incorrectCount;
        private int unansweredCount;

        private SubjectAccumulator(String subjectId, String subjectName) {
            this.subjectId = subjectId;
            this.subjectName = subjectName;
        }
    }

    public record GeneratedAuditPdf(byte[] content, String filename) {
    }

    public record SubjectAuditSummary(
            String subjectId,
            String subjectName,
            int totalQuestions,
            int answeredCount,
            int correctCount,
            int incorrectCount,
            int unansweredCount,
            double percentage
    ) {
    }

    public record QuestionAuditItem(
            int questionNumber,
            int subjectQuestionNumber,
            String questionId,
            String subjectId,
            String subjectName,
            String topic,
            String sharedContext,
            String prompt,
            List<Map<String, Object>> assets,
            Map<String, String> options,
            String selectedOption,
            String correctOption,
            String outcome,
            boolean flagged
    ) {
    }

    public record ExamScriptAuditDto(
            UUID sessionId,
            UUID studentId,
            String studentName,
            String registrationNumber,
            String status,
            String submissionTrigger,
            Instant startTime,
            Instant expectedEndTime,
            Instant endTime,
            Instant lastClientSyncAt,
            int tabSwitchCount,
            String answerReceiptCode,
            String answerSha256,
            int totalQuestions,
            int answeredCount,
            int correctCount,
            int incorrectCount,
            int unansweredCount,
            double percentage,
            List<SubjectAuditSummary> subjectSummaries,
            List<QuestionAuditItem> questions
    ) {
    }
}
