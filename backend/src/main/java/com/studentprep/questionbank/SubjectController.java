package com.studentprep.questionbank;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

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
    public List<Subject> getAllSubjects() {
        return subjectRepository.findAll();
    }

    @GetMapping("/admin/subjects")
    public List<Subject> getAllSubjectsAdmin() {
        return subjectRepository.findAll();
    }

    @PostMapping("/admin/subjects")
    public ResponseEntity<Subject> createSubject(@RequestBody Subject subject) {
        if (subject.getName() == null || subject.getName().trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        Subject savedSubject = subjectRepository.save(subject);
        return ResponseEntity.ok(savedSubject);
    }

    @GetMapping("/admin/subjects/{id}")
    public ResponseEntity<Subject> getSubjectById(@PathVariable UUID id) {
        return subjectRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/admin/subjects/{id}")
    public ResponseEntity<Subject> updateSubject(@PathVariable UUID id, @RequestBody Subject updatedSubject) {
        if (updatedSubject.getName() == null || updatedSubject.getName().trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        return subjectRepository.findById(id)
                .map(subject -> {
                    subject.setName(updatedSubject.getName());
                    return ResponseEntity.ok(subjectRepository.save(subject));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/admin/subjects/{id}")
    public ResponseEntity<Void> deleteSubject(@PathVariable UUID id) {
        if (!subjectRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }

        if (questionRepository.existsBySubjectId(id)) {
            return ResponseEntity.badRequest().build(); // or use a specific error response
        }

        subjectRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
