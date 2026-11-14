package com.studentprep.student;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.studentprep.common.PdfStyle;
import com.studentprep.questionbank.Subject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class RegistrationSlipGenerator {

    public static final String REDACTED_CREDENTIAL_NOTICE =
            "Login PIN: Issued separately (hashed) — use Admin PIN Reset to issue a new one-time PIN";

    private static final Font TITLE_FONT = new Font(Font.HELVETICA, 15f, Font.BOLD, PdfStyle.JAMB_GREEN);
    private static final Font META_FONT = new Font(Font.HELVETICA, 9.5f, Font.NORMAL, PdfStyle.SLATE_600);
    private static final Font HEADER_FONT = new Font(Font.HELVETICA, 9.5f, Font.BOLD, Color.WHITE);
    private static final Font CELL_FONT = new Font(Font.HELVETICA, 9f, Font.NORMAL, PdfStyle.SLATE_900);
    private static final Font REG_FONT = new Font(Font.COURIER, 8.5f, Font.NORMAL, PdfStyle.SLATE_900);
    private static final Font PIN_FONT = new Font(Font.COURIER, 9.5f, Font.BOLD, PdfStyle.SLATE_900);
    private static final Font EMPTY_FONT = new Font(Font.HELVETICA, 11f, Font.ITALIC, PdfStyle.SLATE_600);

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    private static final float[] ROSTER_COLUMN_WIDTHS = {0.8f, 4.4f, 3.2f, 1.6f};

    private final StudentRepository studentRepository;
    private final StudentManagementService studentManagementService;

    public RegistrationSlipGenerator(
            StudentRepository studentRepository,
            StudentManagementService studentManagementService) {
        this.studentRepository = studentRepository;
        this.studentManagementService = studentManagementService;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getRegistrationSlip(UUID id) {
        Student student = studentRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Student not found"));

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("name", student.getName());
        details.put("registrationNumber", student.getRegistrationNumber());
        details.put("pinResetRequired", student.isPinResetRequired());
        details.put("credentialStatus", REDACTED_CREDENTIAL_NOTICE);
        details.put("subjects", student.getSubjects().stream()
                .map(Subject::getName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList());
        return details;
    }

    /**
     * Generates a printable single-student registration and credential slip PDF containing the
     * candidate's active decrypted Login PIN, lazily provisioning one if the account is unissued.
     */
    @Transactional
    public byte[] getRegistrationSlipPdf(UUID id) {
        Student student = studentRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Student not found"));
        String activePin = studentManagementService.ensureRecoverablePin(student);

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, 40f, 40f, 40f, 40f);
            PdfWriter.getInstance(document, baos);
            document.open();

            document.add(new Paragraph("JAMB CBT Registration & Examination Slip"));
            document.add(new Paragraph("=================================================="));
            document.add(new Paragraph("Candidate Name: " + safeText(student.getName())));
            document.add(new Paragraph("Registration Number: " + safeText(student.getRegistrationNumber())));
            document.add(new Paragraph("Login PIN: " + activePin));

            String subjects = student.getSubjects().stream()
                    .map(Subject::getName)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.joining(", "));
            document.add(new Paragraph("Registered Subjects: " + (subjects.isEmpty() ? "None" : subjects)));
            document.add(new Paragraph("=================================================="));
            document.add(new Paragraph(
                    "Important: Use your Registration Number and confidential Login PIN to log in on exam day."));

            document.close();
            return baos.toByteArray();
        } catch (DocumentException | IOException ex) {
            throw new IllegalStateException("Failed to generate registration slip PDF", ex);
        }
    }

    /**
     * Generates a multi-page A4 credential roster PDF for all registered students.
     */
    @Transactional
    public byte[] getBulkCredentialsPdf() {
        List<Student> students = studentRepository.findAllByOrderByNameAscRegistrationNumberAsc();
        Map<UUID, String> pinsByStudentId = studentManagementService.ensureRecoverablePins(students);
        return renderCredentialRosterPdf(students, pinsByStudentId, "Scope: All Registered Students");
    }

    /**
     * Generates a multi-page A4 credential roster PDF strictly for the authoritative database
     * student rows matching {@code registrationNumbers}.
     */
    @Transactional
    public byte[] getBatchCredentialsPdf(List<String> registrationNumbers) {
        List<String> cleanedRegNumbers = registrationNumbers == null
                ? List.of()
                : registrationNumbers.stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(reg -> !reg.isEmpty())
                        .distinct()
                        .toList();

        List<Student> students = cleanedRegNumbers.isEmpty()
                ? List.of()
                : studentRepository.findByRegistrationNumberInOrderByNameAscRegistrationNumberAsc(cleanedRegNumbers);

        Map<UUID, String> pinsByStudentId = studentManagementService.ensureRecoverablePins(students);
        return renderCredentialRosterPdf(students, pinsByStudentId, "Scope: Selected Batch Roster");
    }

    private byte[] renderCredentialRosterPdf(
            List<Student> students,
            Map<UUID, String> pinsByStudentId,
            String scopeLabel) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, 32f, 32f, 36f, 36f);
            PdfWriter.getInstance(document, baos);
            document.open();

            addRosterBanner(document, scopeLabel, students.size());

            if (students.isEmpty()) {
                Paragraph emptyNotice = new Paragraph("No registered students found.", EMPTY_FONT);
                emptyNotice.setSpacingBefore(12f);
                document.add(emptyNotice);
            } else {
                document.add(buildCredentialTable(students, pinsByStudentId));
            }

            document.close();
            return baos.toByteArray();
        } catch (DocumentException | IOException ex) {
            throw new IllegalStateException("Failed to generate student credential roster PDF", ex);
        }
    }

    private void addRosterBanner(Document document, String scopeLabel, int totalCount) throws DocumentException {
        Paragraph title = new Paragraph("StudentPrep Examination Credential Roster", TITLE_FONT);
        title.setSpacingAfter(4f);
        document.add(title);

        Paragraph metadata = new Paragraph(
                scopeLabel + "   |   Generated: " + TIMESTAMP_FORMATTER.format(Instant.now())
                        + "   |   Total Candidates: " + totalCount,
                META_FONT);
        metadata.setSpacingAfter(2f);
        document.add(metadata);

        Paragraph notice = new Paragraph(
                "Confidential: For authorized examination administrator distribution only.",
                META_FONT);
        notice.setSpacingAfter(10f);
        document.add(notice);
    }

    private PdfPTable buildCredentialTable(List<Student> students, Map<UUID, String> pinsByStudentId)
            throws DocumentException {
        PdfPTable table = new PdfPTable(ROSTER_COLUMN_WIDTHS);
        table.setWidthPercentage(100f);
        table.setHeaderRows(1);
        table.setSplitRows(false);

        addHeaderCell(table, "#", Element.ALIGN_CENTER);
        addHeaderCell(table, "Candidate Name", Element.ALIGN_LEFT);
        addHeaderCell(table, "Registration Number", Element.ALIGN_LEFT);
        addHeaderCell(table, "Login PIN", Element.ALIGN_CENTER);

        for (int i = 0; i < students.size(); i++) {
            Student student = students.get(i);
            String pin = pinsByStudentId.get(student.getId());
            if (pin == null || pin.isBlank()) {
                throw new IllegalStateException(
                        "Missing recoverable PIN for student " + PdfStyle.safeText(student.getRegistrationNumber()));
            }
            Color rowBackground = (i % 2 == 1) ? PdfStyle.SLATE_50 : Color.WHITE;

            addBodyCell(table, String.valueOf(i + 1), CELL_FONT, Element.ALIGN_CENTER, rowBackground);
            addBodyCell(table, PdfStyle.safeText(student.getName()), CELL_FONT, Element.ALIGN_LEFT, rowBackground);
            addBodyCell(
                    table,
                    PdfStyle.safeText(student.getRegistrationNumber()),
                    REG_FONT,
                    Element.ALIGN_LEFT,
                    rowBackground);
            addBodyCell(table, pin, PIN_FONT, Element.ALIGN_CENTER, rowBackground);
        }

        return table;
    }

    private static void addHeaderCell(PdfPTable table, String text, int alignment) {
        table.addCell(PdfStyle.createHeaderCell(text, HEADER_FONT, alignment, 6f));
    }

    private static void addBodyCell(
            PdfPTable table,
            String text,
            Font font,
            int alignment,
            Color backgroundColor) {
        table.addCell(PdfStyle.createBodyCell(text, font, alignment, backgroundColor, PdfStyle.SLATE_300, 5f));
    }

    private static String safeText(String value) {
        return PdfStyle.safeText(value);
    }
}
