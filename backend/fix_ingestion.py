import re

file_path = 'c:/Users/User/Desktop/studentprep/backend/src/main/java/com/studentprep/ingestion/IngestionController.java'
with open(file_path, 'r') as f:
    content = f.read()

content = re.sub(r'com\.studentprep\.common\.ApiResponse', 'ApiResponse', content)

content = re.sub(r'import java\.util\.UUID;', 'import java.util.UUID;\nimport com.studentprep.common.ApiResponse;', content)

with open(file_path, 'w') as f:
    f.write(content)
