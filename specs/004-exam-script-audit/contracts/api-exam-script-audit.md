# API Contract: Exam Script Audit & Cryptographic Answer Receipt (`004-exam-script-audit`)

## 1. `GET /api/v1/admin/exams/sessions/{sessionId}/script`

Reconstructs the full frozen question paper, candidate responses, cryptographic answer receipt, and timeline provenance for an examination sitting.

- **Authorization**: `ROLE_ADMIN`, `ROLE_SUPERVISOR` (`401 Unauthorized` if unauthenticated, `403 Forbidden` for `ROLE_STUDENT`, `404 Not Found` if `sessionId` does not exist).
- **Response (`200 OK`)**:
```json
{
  "data": {
    "sessionId": "859b027d-8fe1-4af2-928a-c89134c99ec7",
    "studentId": "dcd948ab-d2b3-48a6-8e7f-0ce5a411f0bb",
    "studentName": "Hadiza",
    "registrationNumber": "342570UE",
    "status": "SUBMITTED",
    "submissionTrigger": "MANUAL_SUBMIT",
    "startTime": "2026-11-15T02:44:54.173589Z",
    "expectedEndTime": "2026-11-15T04:44:54.173589Z",
    "endTime": "2026-11-15T03:20:03.478805Z",
    "lastClientSyncAt": "2026-11-15T03:20:03.112Z",
    "tabSwitchCount": 0,
    "answerReceiptCode": "8F3A-91C2-E410",
    "answerSha256": "8f3a91c2e410...",
    "totalQuestions": 195,
    "answeredCount": 195,
    "correctCount": 139,
    "incorrectCount": 56,
    "unansweredCount": 0,
    "percentage": 71.28,
    "subjectSummaries": [
      {
        "subjectId": "cba22ec1-17cf-441e-aa08-33644c0cfec1",
        "subjectName": "MATHEMATICS",
        "totalQuestions": 45,
        "answeredCount": 45,
        "correctCount": 21,
        "incorrectCount": 24,
        "unansweredCount": 0,
        "percentage": 46.67
      }
    ],
    "questions": [
      {
        "questionNumber": 1,
        "subjectQuestionNumber": 1,
        "questionId": "3ea96dd1-f186-43f3-bfce-cdb830809732",
        "subjectId": "cba22ec1-17cf-441e-aa08-33644c0cfec1",
        "subjectName": "MATHEMATICS",
        "topic": "General Assessment",
        "sharedContext": null,
        "prompt": "Find the angle...",
        "assets": [],
        "options": {
          "A": "$90^\\circ$",
          "B": "$30^\\circ$",
          "C": "$150^\\circ$",
          "D": "$120^\\circ$",
          "E": "$60^\\circ$"
        },
        "selectedOption": "E",
        "correctOption": "E",
        "outcome": "CORRECT",
        "flagged": false
      }
    ]
  },
  "meta": {
    "timestamp": "2026-11-15T03:50:00Z"
  }
}
```

---

## 2. `GET /api/v1/admin/exams/sessions/{sessionId}/script/pdf`

Generates a printable A4 PDF examination script audit report.

- **Authorization**: `ROLE_ADMIN`, `ROLE_SUPERVISOR` (`401 Unauthorized` if unauthenticated, `403 Forbidden` for `ROLE_STUDENT`, `404 Not Found` if `sessionId` does not exist).
- **Response (`200 OK`)**:
  - `Content-Type: application/pdf`
  - `Content-Disposition: attachment; filename="<CandidateName>_<RegNo>-Exam-Script-Audit.pdf"`

---

## 3. Candidate Result Receipt Extensions (`GET /api/v1/student/results` & `/api/v1/students/me/results`)

Adds `examSessionId` and `answerReceiptCode` (`XXXX-XXXX-XXXX`) to candidate result responses and prints the `Answer Receipt Code` on candidate Result Slip PDFs so candidates can verify their receipt code against the administrative audit record.
