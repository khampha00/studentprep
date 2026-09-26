package com.studentprep.student;

import com.studentprep.questionbank.Subject;
import com.studentprep.questionbank.SubjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class CsvStudentImporter {

    private final StudentRepository studentRepository;
    private final SubjectRepository subjectRepository;
    private final UserRepository userRepository;

    public CsvStudentImporter(StudentRepository studentRepository, SubjectRepository subjectRepository, UserRepository userRepository) {
        this.studentRepository = studentRepository;
        this.subjectRepository = subjectRepository;
        this.userRepository = userRepository;
    }

    private Subject findOrCreateSubject(String name) {
        String cleanName = name.trim();
        if (cleanName.isEmpty()) return null;

        Optional<Subject> exact = subjectRepository.findByName(cleanName);
        if (exact.isPresent()) {
            return exact.get();
        }

        List<Subject> allSubjects = subjectRepository.findAll();

        for (Subject s : allSubjects) {
            if (s.getName().equalsIgnoreCase(cleanName)) {
                return s;
            }
        }

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

        Subject newSubject = new Subject();
        newSubject.setName(cleanName.toUpperCase());
        return subjectRepository.save(newSubject);
    }

    @Transactional
    public Map<String, Object> processBulkCsv(MultipartFile csvFile) {
        int created = 0;
        List<String> errors = new ArrayList<>();
        List<Map<String, String>> credentials = new ArrayList<>();
        java.security.SecureRandom secureRandom = new java.security.SecureRandom();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(csvFile.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                if (firstLine) { 
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

                    String rawPin = String.valueOf(10000 + secureRandom.nextInt(90000));
                    student.setPin(rawPin);

                    String regNum;
                    do {
                        regNum = generateRegistrationNumber();
                    } while (studentRepository.existsByRegistrationNumber(regNum));

                    student.setRegistrationNumber(regNum);
                    studentRepository.save(student);

                    User user = new User();
                    user.setId(studentId);
                    user.setIdentifier(regNum);
                    user.setPin(rawPin);
                    user.setRole("ROLE_STUDENT");
                    userRepository.save(user);

                    credentials.add(Map.of("registrationNumber", regNum, "pin", rawPin));
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
        result.put("credentials", credentials);
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
    public byte[] exportStudentsCsv() {
        List<Student> allStudents = studentRepository.findAll();
        StringBuilder sb = new StringBuilder();
        sb.append("Name,Registration Number,Login PIN,State,Exam Center,Registered Subjects\n");
        for (Student s : allStudents) {
            String subjects = s.getSubjects().stream().map(Subject::getName).collect(Collectors.joining("; "));
            sb.append("\"").append(s.getName() != null ? s.getName().replace("\"", "\"\"") : "").append("\",")
              .append("\"").append(s.getRegistrationNumber()).append("\",")
              .append("\"").append(s.getPin()).append("\",")
              .append("\"").append(s.getState() != null ? s.getState().replace("\"", "\"\"") : "").append("\",")
              .append("\"").append(s.getExamCenter() != null ? s.getExamCenter().replace("\"", "\"\"") : "").append("\",")
              .append("\"").append(subjects.replace("\"", "\"\"")).append("\"\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
}
