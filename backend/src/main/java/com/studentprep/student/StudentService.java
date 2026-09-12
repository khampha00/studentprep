package com.studentprep.student;

import com.studentprep.questionbank.Subject;
import com.studentprep.questionbank.SubjectRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.io.ByteArrayOutputStream;
import java.util.stream.Collectors;
import com.lowagie.text.Document;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;

@Service
public class StudentService {

    private final StudentRepository studentRepository;
    private final SubjectRepository subjectRepository;
    private final PasswordEncoder passwordEncoder;

    public StudentService(StudentRepository studentRepository, SubjectRepository subjectRepository, PasswordEncoder passwordEncoder) {
        this.studentRepository = studentRepository;
        this.subjectRepository = subjectRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public Map<String, Object> processBulkCsv(MultipartFile csvFile) {
        int created = 0;
        List<String> errors = new ArrayList<>();
        String defaultPinHash = passwordEncoder.encode("12345");

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(csvFile.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                if (firstLine) { // Skip header
                    firstLine = false;
                    continue;
                }
                if (line.trim().isEmpty()) continue;
                String[] parts = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
                for (int i = 0; i < parts.length; i++) {
                    parts[i] = parts[i].replaceAll("^\"|\"$", "").replace("\"\"", "\"");
                }
                if (parts.length < 7) {
                    errors.add("Invalid format: " + line);
                    continue;
                }

                try {
                    String name = parts[0].trim();
                    String state = parts[1].trim();
                    String center = parts[2].trim();

                    Set<Subject> subjects = new HashSet<>();
                    for (int i = 3; i < 7; i++) {
                        String subjectName = parts[i].trim();
                        if (subjectName.isEmpty()) continue;
                        Subject subject = subjectRepository.findByName(subjectName)
                                .orElseThrow(() -> new IllegalArgumentException("Subject not found: " + subjectName));
                        subjects.add(subject);
                    }

                    Student student = new Student();
                    student.setName(name);
                    student.setState(state);
                    student.setExamCenter(center);
                    student.setSubjects(subjects);
                    student.setPinHash(defaultPinHash);

                    String regNum;
                    do {
                        regNum = generateRegistrationNumber();
                    } while (studentRepository.existsByRegistrationNumber(regNum));

                    student.setRegistrationNumber(regNum);

                    studentRepository.save(student);
                    created++;
                } catch (Exception e) {
                    errors.add("Error processing line '" + line + "': " + e.getMessage());
                }
            }
        } catch (Exception e) {
            errors.add("Failed to read file: " + e.getMessage());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("created", created);
        result.put("errors", errors);
        return result;
    }

    private String generateRegistrationNumber() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder("JAMB-2026-");
        Random random = new Random();
        for (int i = 0; i < 6; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getRegistrationSlip(UUID id) {
        Student student = studentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Student not found"));
        
        Map<String, Object> details = new HashMap<>();
        details.put("name", student.getName());
        details.put("registrationNumber", student.getRegistrationNumber());
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
            
            document.add(new Paragraph("JAMB Registration Slip"));
            document.add(new Paragraph("--------------------------------------------------"));
            document.add(new Paragraph("Student Name: " + student.getName()));
            document.add(new Paragraph("Registration Number: " + student.getRegistrationNumber()));
            document.add(new Paragraph("State: " + student.getState()));
            document.add(new Paragraph("Exam Center: " + student.getExamCenter()));
            
            String subjects = student.getSubjects().stream()
                .map(Subject::getName)
                .collect(Collectors.joining(", "));
            document.add(new Paragraph("Enrolled Subjects: " + subjects));
            
            document.close();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate PDF slip", e);
        }
    }
}
