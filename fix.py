import sys

with open('frontend/src/store/examSlice.ts', 'r') as f:
    content = f.read()

old_submit = 'await axios.post(`/api/v1/exams/active/submit${submitParam}`);'
new_submit = '''const token = localStorage.getItem('token');
                await fetch(`/api/v1/exams/active/submit${submitParam}`, {
                    method: 'POST',
                    headers: {
                        'Authorization': `Bearer ${token}`
                    },
                    keepalive: true
                });'''

old_sync = '''await axios.post(`/api/v1/exams/active/sync${sessionParam}`, {
                statePayload: {
                    answers: state.answers,
                    timeLeft: state.timeLeft,
                    lastUpdated: state.lastUpdated,
                    isFinal: isFinalSync,
                    reason: reason
                }
            });'''
new_sync = '''const token = localStorage.getItem('token');
            await fetch(`/api/v1/exams/active/sync${sessionParam}`, {
                method: 'POST',
                headers: {
                    'Authorization': `Bearer ${token}`,
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({
                    statePayload: {
                        answers: state.answers,
                        timeLeft: state.timeLeft,
                        lastUpdated: state.lastUpdated,
                        isFinal: isFinalSync,
                        reason: reason
                    }
                }),
                keepalive: isFinalSync
            });'''

# Wait, spacing might not match perfectly.
# Using regex for sync replacement to ignore exact whitespace
import re
sync_pattern = r"await axios\.post\(`/api/v1/exams/active/sync\$\{sessionParam\}`,\s*\{\s*statePayload:\s*\{\s*answers:\s*state\.answers,\s*timeLeft:\s*state\.timeLeft,\s*lastUpdated:\s*state\.lastUpdated,\s*isFinal:\s*isFinalSync,\s*reason:\s*reason\s*\}\s*\}\);"

content = content.replace(old_submit, new_submit)
content = re.sub(sync_pattern, new_sync, content)

with open('frontend/src/store/examSlice.ts', 'w') as f:
    f.write(content)

print('Success')
