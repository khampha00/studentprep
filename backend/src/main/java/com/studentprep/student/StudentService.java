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
    private final UserRepository userRepository;

    public StudentService(StudentRepository studentRepository, SubjectRepository subjectRepository, PasswordEncoder passwordEncoder, UserRepository userRepository) {
        this.studentRepository = studentRepository;
        this.subjectRepository = subjectRepository;
        this.passwordEncoder = passwordEncoder;
        this.userRepository = userRepository;
    }

    private Subject findOrCreateSubject(String name) {
        String cleanName = name.trim();
        if (cleanName.isEmpty()) return null;

        // 1. Direct match
        Optional<Subject> exact = subjectRepository.findByName(cleanName);
        if (exact.isPresent()) {
            return exact.get();
        }

        List<Subject> allSubjects = subjectRepository.findAll();

        // 2. Case-insensitive match
        for (Subject s : allSubjects) {
            if (s.getName().equalsIgnoreCase(cleanName)) {
                return s;
            }
        }

        // 3. Normalized alias matching (English Language / Use of English, Mathematics / Maths)
        String normalized = cleanName.toUpperCase().replaceAll("[_\\- ]+", " ").trim();
        if (normalized.equals("ENGLISH") || normalized.equals("USE OF ENGLISH") || normalized.equals("ENGLISH LANGUAGE")) {
            for (Subject s : allSubjects) {
                String sNorm = s.getName().toUpperCase().replaceAll("[_\\- ]+", " ").trim();
                if (sNorm.equals("ENGLISH") || sNorm.equals("USE OF ENGLISH") || sNorm.equals("ENGLISH LANGUAGE")) {
                    return s;
                }
            }
        }
        if (normalized.equals("MATH") || normalized.equals("MATHS") || normalized.equals("MATHEMATICS")) {
            for (Subject s : allSubjects) {
                String sNorm = s.getName().toUpperCase().replaceAll("[_\\- ]+", " ").trim();
                if (sNorm.equals("MATH") || sNorm.equals("MATHS") || sNorm.equals("MATHEMATICS")) {
                    return s;
                }
            }
        }

        // 4. Auto-provision new subject in standard uppercase format
        Subject newSubject = new Subject();
        newSubject.setName(cleanName.toUpperCase());
        return subjectRepository.save(newSubject);
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
                        Subject subject = findOrCreateSubject(subjectName);
                        if (subject != null) {
                            subjects.add(subject);
                        }
                    }

                    UUID studentId = UUID.randomUUID();
                    Student student = new Student();
                    student.setId(studentId);
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

                    // Sync student credential to users table for login and exam_sessions foreign key
                    User user = new User();
                    user.setId(studentId);
                    user.setIdentifier(regNum);
                    user.setPinHash(defaultPinHash);
                    user.setRole("ROLE_STUDENT");
                    userRepository.save(user);

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
