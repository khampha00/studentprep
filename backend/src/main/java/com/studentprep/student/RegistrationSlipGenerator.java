package com.studentprep.student;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;
import com.studentprep.questionbank.Subject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class RegistrationSlipGenerator {

    public static final String REDACTED_CREDENTIAL_NOTICE =
            "Login PIN: Issued separately (hashed) — use Admin PIN Reset to issue a new one-time PIN";

    private final StudentRepository studentRepository;

    public RegistrationSlipGenerator(StudentRepository studentRepository) {
        this.studentRepository = studentRepository;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getRegistrationSlip(UUID id) {
        Student student = studentRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Student not found"));

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("name", student.getName());
        details.put("registrationNumber", student.getRegistrationNumber());
        details.put("examCenter", student.getExamCenter() != null ? student.getExamCenter() : "Main Center");
        details.put("pinResetRequired", student.isPinResetRequired());
        details.put("credentialStatus", REDACTED_CREDENTIAL_NOTICE);
        details.put("subjects", student.getSubjects().stream()
                .map(Subject::getName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList());
        return details;
    }

    @Transactional(readOnly = true)
    public byte[] getRegistrationSlipPdf(UUID id) {
        Student student = studentRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Student not found"));

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Document document = new Document();
            PdfWriter.getInstance(document, baos);
            document.open();

            document.add(new Paragraph("JAMB CBT Registration & Examination Slip"));
            document.add(new Paragraph("=================================================="));
            document.add(new Paragraph("Candidate Name: " + student.getName()));
            document.add(new Paragraph("Registration Number: " + student.getRegistrationNumber()));
            document.add(new Paragraph(REDACTED_CREDENTIAL_NOTICE));
            document.add(new Paragraph("Exam Center: "
                    + (student.getExamCenter() != null ? student.getExamCenter() : "Main Center")));

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
}
