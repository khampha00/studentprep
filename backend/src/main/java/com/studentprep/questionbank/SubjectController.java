package com.studentprep.questionbank;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import com.studentprep.common.ApiResponse;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class SubjectController {

    private final SubjectRepository subjectRepository;
    private final QuestionRepository questionRepository;

    @Autowired
    public SubjectController(SubjectRepository subjectRepository, QuestionRepository questionRepository) {
        this.subjectRepository = subjectRepository;
        this.questionRepository = questionRepository;
    }

    @GetMapping("/subjects")
    public ResponseEntity<ApiResponse<List<Subject>>> getAllSubjects() {
        return ResponseEntity.ok(ApiResponse.of(subjectRepository.findAll()));
    }

    @GetMapping("/admin/subjects")
    public ResponseEntity<ApiResponse<List<Subject>>> getAllSubjectsAdmin() {
        return ResponseEntity.ok(ApiResponse.of(subjectRepository.findAll()));
    }

    @PostMapping("/admin/subjects")
    public ResponseEntity<ApiResponse<Subject>> createSubject(@RequestBody Subject subject) {
        if (subject.getName() == null || subject.getName().trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        Subject savedSubject = subjectRepository.save(subject);
        return ResponseEntity.ok(ApiResponse.of(savedSubject));
    }

    @GetMapping("/admin/subjects/{id}")
    public ResponseEntity<ApiResponse<Subject>> getSubjectById(@PathVariable UUID id) {
        return subjectRepository.findById(id)
                .map(s -> ResponseEntity.ok(ApiResponse.of(s)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/admin/subjects/{id}")
    public ResponseEntity<ApiResponse<Subject>> updateSubject(@PathVariable UUID id, @RequestBody Subject updatedSubject) {
        if (updatedSubject.getName() == null || updatedSubject.getName().trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        return subjectRepository.findById(id)
                .map(subject -> {
                    subject.setName(updatedSubject.getName());
                    return ResponseEntity.ok(ApiResponse.of(subjectRepository.save(subject)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/admin/subjects/{id}")
    public ResponseEntity<ApiResponse<Map<String, String>>> deleteSubject(@PathVariable UUID id) {
        if (!subjectRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }

        if (questionRepository.existsBySubjectId(id)) {
            return ResponseEntity.badRequest().build(); // or use a specific error response
        }

        subjectRepository.deleteById(id);
        return ResponseEntity.ok(ApiResponse.of(Map.of("status", "DELETED")));
    }
}
