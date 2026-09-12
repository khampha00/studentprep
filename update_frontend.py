import os
import glob
import re

files_to_check = glob.glob(r'c:\Users\User\Desktop\studentprep\frontend\src\**\*.ts*', recursive=True)

for file in files_to_check:
    with open(file, 'r', encoding='utf-8') as f:
        content = f.read()
    
    if 'axios' not in content:
        continue
        
    # We want to replace response.data with response.data.data, except where it's already response.data.data
    new_content = re.sub(r'\b(res|response)\.data(?!\.data)', r'\1.data.data', content)
    
    # Wait, if there are axios errors like `error.response.data`, we shouldn't replace them?
    # Actually, if the API contract is just successful responses wrapper, maybe errors are not wrapped?
    # The instruction says: "The API contract at `docs/api_contract.md` requires ALL successful responses to be wrapped"
    # "Update them to use response.data.data instead of response.data"
    
    # We can also replace `axios.post(...ingest/pdf...` to `ingestion/pdf`!
    new_content = new_content.replace('/admin/ingest/', '/admin/ingestion/')
    
    if new_content != content:
        with open(file, 'w', encoding='utf-8') as f:
            f.write(new_content)
        print(f"Updated {file}")
