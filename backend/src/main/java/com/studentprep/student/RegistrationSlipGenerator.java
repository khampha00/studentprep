package com.studentprep.student;

import com.lowagie.text.Document;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;
import com.studentprep.questionbank.Subject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class RegistrationSlipGenerator {

    private final StudentRepository studentRepository;

    public RegistrationSlipGenerator(StudentRepository studentRepository) {
        this.studentRepository = studentRepository;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getRegistrationSlip(UUID id) {
        Student student = studentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Student not found"));
        
        Map<String, Object> details = new HashMap<>();
        details.put("name", student.getName());
        details.put("registrationNumber", student.getRegistrationNumber());
        details.put("pin", student.getPin());
        details.put("examCenter", student.getExamCenter());
        details.put("state", student.getState());
        details.put("subjects", student.getSubjects().stream().map(Subject::getName).toList());
        
        return details;
    }

    @Transactional(readOnly = true)
    public byte[] getRegistrationSlipPdf(UUID id) {
        Student student = studentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Student not found"));

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Document document = new Document();
            PdfWriter.getInstance(document, baos);
            document.open();
            
            document.add(new Paragraph("JAMB CBT Registration & Examination Slip"));
            document.add(new Paragraph("=================================================="));
            document.add(new Paragraph("Candidate Name: " + student.getName()));
            document.add(new Paragraph("Registration Number: " + student.getRegistrationNumber()));
            document.add(new Paragraph("Login PIN: " + student.getPin()));
            document.add(new Paragraph("State: " + student.getState()));
            document.add(new Paragraph("Exam Center: " + (student.getExamCenter() != null ? student.getExamCenter() : "Main Center")));
            
            String subjects = student.getSubjects().stream()
                .map(Subject::getName)
                .collect(Collectors.joining(", "));
            document.add(new Paragraph("Registered Subjects: " + (subjects.isEmpty() ? "None" : subjects)));
            document.add(new Paragraph("=================================================="));
            document.add(new Paragraph("Important: Use your Registration Number and Login PIN to log in on exam day."));
            
            document.close();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate PDF slip", e);
        }
    }
}
