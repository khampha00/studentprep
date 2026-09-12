import re

file_path = 'c:/Users/User/Desktop/studentprep/backend/src/main/java/com/studentprep/questionbank/QuestionController.java'
with open(file_path, 'r') as f:
    content = f.read()

content = re.sub(r'import java\.util\.UUID;', 'import java.util.UUID;\nimport com.studentprep.common.ApiResponse;\nimport java.util.Map;', content)

content = re.sub(r'ResponseEntity<Void>', 'ResponseEntity<ApiResponse<Map<String, String>>>', content)
content = re.sub(r'ResponseEntity<\?>', 'ResponseEntity<ApiResponse<Map<String, String>>>', content)
content = re.sub(r'ResponseEntity\.noContent\(\)\.build\(\)', 'ResponseEntity.ok(ApiResponse.of(Map.of("status", "DELETED")))', content)
content = re.sub(r'ResponseEntity\.ok\(\)\.build\(\)', 'ResponseEntity.ok(ApiResponse.of(Map.of("status", "SUCCESS")))', content)
content = re.sub(r'ResponseEntity\.badRequest\(\)\.body\((.*?)\)', r'ResponseEntity.badRequest().body(ApiResponse.of(Map.of("error", \1)))', content)

with open(file_path, 'w') as f:
    f.write(content)
