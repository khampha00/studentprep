package com.studentprep.student;

import com.studentprep.questionbank.Subject;
import com.studentprep.questionbank.SubjectRepository;
import com.studentprep.student.StudentManagementService.ProvisionedStudent;
import com.studentprep.student.dto.BulkCredentialResponse;
import com.studentprep.student.dto.BulkImportResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class CsvStudentImporter {

    private static final String CSV_SPLIT_REGEX = ",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)";
    private static final int MAX_FIELD_LENGTH = 255;

    private final StudentRepository studentRepository;
    private final SubjectRepository subjectRepository;
    private final StudentManagementService studentManagementService;

    public CsvStudentImporter(
            StudentRepository studentRepository,
            SubjectRepository subjectRepository,
            StudentManagementService studentManagementService) {
        this.studentRepository = studentRepository;
        this.subjectRepository = subjectRepository;
        this.studentManagementService = studentManagementService;
    }

    private Subject findOrCreateSubject(String name) {
        String cleanName = name.trim();
        if (cleanName.isEmpty()) {
            return null;
        }

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

        String normalized = cleanName.toUpperCase(Locale.ROOT).replaceAll("[_\\- ]+", " ").trim();
        if ("ENGLISH".equals(normalized)
                || "USE OF ENGLISH".equals(normalized)
                || "ENGLISH LANGUAGE".equals(normalized)) {
            for (Subject s : allSubjects) {
                String sNorm = s.getName().toUpperCase(Locale.ROOT).replaceAll("[_\\- ]+", " ").trim();
                if ("ENGLISH".equals(sNorm) || "USE OF ENGLISH".equals(sNorm) || "ENGLISH LANGUAGE".equals(sNorm)) {
                    return s;
                }
            }
        }
        if ("MATH".equals(normalized) || "MATHS".equals(normalized) || "MATHEMATICS".equals(normalized)) {
            for (Subject s : allSubjects) {
                String sNorm = s.getName().toUpperCase(Locale.ROOT).replaceAll("[_\\- ]+", " ").trim();
                if ("MATH".equals(sNorm) || "MATHS".equals(sNorm) || "MATHEMATICS".equals(sNorm)) {
                    return s;
                }
            }
        }

        Subject newSubject = new Subject();
        newSubject.setName(cleanName.toUpperCase(Locale.ROOT));
        return subjectRepository.save(newSubject);
    }

    @Transactional
    public BulkImportResponse processBulkCsv(MultipartFile csvFile) {
        if (csvFile == null || csvFile.isEmpty()) {
            throw new IllegalArgumentException("CSV file must not be empty");
        }

        int created = 0;
        List<String> errors = new ArrayList<>();
        List<BulkCredentialResponse> credentials = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(csvFile.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean headerProcessed = false;
            int dataRowNumber = 0;

            while ((line = reader.readLine()) != null) {
                if (!headerProcessed) {
                    if (line.startsWith("\uFEFF")) {
                        line = line.substring(1);
                    }
                    headerProcessed = true;
                    continue;
                }
                if (line.trim().isEmpty()) {
                    continue;
                }
                dataRowNumber++;

                String[] parts = line.split(CSV_SPLIT_REGEX, -1);
                for (int i = 0; i < parts.length; i++) {
                    parts[i] = unquoteCsvCell(parts[i]);
                }

                if (parts.length < 5) {
                    errors.add("Row " + dataRowNumber + ": invalid format (expected 5 columns)");
                    continue;
                }

                String name = parts[0];
                if (name.isEmpty()) {
                    errors.add("Row " + dataRowNumber + ": student name is required");
                    continue;
                }
                if (name.length() > MAX_FIELD_LENGTH) {
                    errors.add("Row " + dataRowNumber + ": field exceeds maximum length of 255 characters");
                    continue;
                }

                try {
                    Set<Subject> subjects = new HashSet<>();
                    for (int i = 1; i < 5; i++) {
                        String subjectName = parts[i];
                        if (subjectName.isEmpty()) {
                            continue;
                        }
                        Subject subject = findOrCreateSubject(subjectName);
                        if (subject != null) {
                            subjects.add(subject);
                        }
                    }

                    ProvisionedStudent provisioned = studentManagementService.createStudentWithSubjects(
                            name,
                            subjects);

                    credentials.add(new BulkCredentialResponse(
                            provisioned.student().getName(),
                            provisioned.student().getRegistrationNumber(),
                            provisioned.oneTimePin()));
                    created++;
                } catch (RuntimeException ex) {
                    errors.add("Row " + dataRowNumber + ": failed to register student");
                }
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("Failed to read CSV file", ex);
        }

        return new BulkImportResponse(created, errors, credentials);
    }

    @Transactional(readOnly = true)
    public byte[] exportStudentsCsv() {
        List<Student> allStudents = studentRepository.findAll();
        StringBuilder sb = new StringBuilder();
        sb.append("Name,Registration Number,Registered Subjects\n");
        for (Student s : allStudents) {
            String subjects = s.getSubjects().stream()
                    .map(Subject::getName)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.joining("; "));
            sb.append(formatCsvField(s.getName())).append(",")
                    .append(formatCsvField(s.getRegistrationNumber())).append(",")
                    .append(formatCsvField(subjects)).append("\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String unquoteCsvCell(String cell) {
        String trimmed = cell.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).replace("\"\"", "\"");
        }
        return trimmed.trim();
    }

    private static String formatCsvField(String raw) {
        String value = raw != null ? raw : "";
        if (needsFormulaNeutralization(value)) {
            value = "'" + value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static boolean needsFormulaNeutralization(String value) {
        if (value.isEmpty()) {
            return false;
        }
        char first = value.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
            return true;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        char firstNonWhitespace = trimmed.charAt(0);
        return firstNonWhitespace == '='
                || firstNonWhitespace == '+'
                || firstNonWhitespace == '-'
                || firstNonWhitespace == '@';
    }
}
