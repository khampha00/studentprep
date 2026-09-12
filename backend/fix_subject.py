import re

file_path = 'c:/Users/User/Desktop/studentprep/backend/src/main/java/com/studentprep/questionbank/SubjectController.java'
with open(file_path, 'r') as f:
    content = f.read()

content = re.sub(r'import java\.util\.UUID;', 'import java.util.UUID;\nimport com.studentprep.common.ApiResponse;\nimport java.util.Map;', content)

# Replace getAllSubjects
content = re.sub(r'public List<Subject> getAllSubjects\(\) {\s*return subjectRepository\.findAll\(\);\s*}', 
    'public ResponseEntity<ApiResponse<List<Subject>>> getAllSubjects() {\n        return ResponseEntity.ok(ApiResponse.of(subjectRepository.findAll()));\n    }', content)

# Replace getAllSubjectsAdmin
content = re.sub(r'public List<Subject> getAllSubjectsAdmin\(\) {\s*return subjectRepository\.findAll\(\);\s*}', 
    'public ResponseEntity<ApiResponse<List<Subject>>> getAllSubjectsAdmin() {\n        return ResponseEntity.ok(ApiResponse.of(subjectRepository.findAll()));\n    }', content)

# Replace createSubject
content = re.sub(r'public ResponseEntity<Subject> createSubject\(@RequestBody Subject subject\) \{', 
    'public ResponseEntity<ApiResponse<Subject>> createSubject(@RequestBody Subject subject) {', content)
content = re.sub(r'return ResponseEntity\.badRequest\(\)\.build\(\);', 
    'return ResponseEntity.badRequest().build();', content)
content = re.sub(r'return ResponseEntity\.ok\(savedSubject\);', 
    'return ResponseEntity.ok(ApiResponse.of(savedSubject));', content)

# Replace getSubjectById
content = re.sub(r'public ResponseEntity<Subject> getSubjectById\(@PathVariable UUID id\) \{', 
    'public ResponseEntity<ApiResponse<Subject>> getSubjectById(@PathVariable UUID id) {', content)
content = re.sub(r'\.map\(ResponseEntity::ok\)', 
    '.map(s -> ResponseEntity.ok(ApiResponse.of(s)))', content)

# Replace updateSubject
content = re.sub(r'public ResponseEntity<Subject> updateSubject\(@PathVariable UUID id, @RequestBody Subject updatedSubject\) \{', 
    'public ResponseEntity<ApiResponse<Subject>> updateSubject(@PathVariable UUID id, @RequestBody Subject updatedSubject) {', content)
content = re.sub(r'return ResponseEntity\.ok\(subjectRepository\.save\(subject\)\);', 
    'return ResponseEntity.ok(ApiResponse.of(subjectRepository.save(subject)));', content)

# Replace deleteSubject
content = re.sub(r'public ResponseEntity<Void> deleteSubject\(@PathVariable UUID id\) \{', 
    'public ResponseEntity<ApiResponse<Map<String, String>>> deleteSubject(@PathVariable UUID id) {', content)
content = re.sub(r'return ResponseEntity\.noContent\(\)\.build\(\);', 
    'return ResponseEntity.ok(ApiResponse.of(Map.of("status", "DELETED")));', content)

with open(file_path, 'w') as f:
    f.write(content)
