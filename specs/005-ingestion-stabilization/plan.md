# Implementation Plan: Ingestion Pipeline Hardening, Audit Hygiene & CI/UI Stabilization

**Branch**: `deployment` (Slice `005-ingestion-stabilization`) | **Date**: 2026-11-15 | **Spec**: [spec.md](file:///c:/Users/User/Desktop/studentprep/specs/005-ingestion-stabilization/spec.md)  
**Input**: Feature specification from [spec.md](file:///c:/Users/User/Desktop/studentprep/specs/005-ingestion-stabilization/spec.md)

## Summary

Slice 005 resolves all remaining pre-deployment application bugs inside the Spring Modulith backend, React Admin UI, and GitHub Actions CI gate (leaving Docker/Nginx container packaging cleanly isolated for Slice 006):
1. **Accurate & Resilient PDF/LLM Ingestion**: Enforces `is_follow_up` semantics cleanly (`false` terminates passage context; `true` links to it) across `AsyncIngestionWorker` and `QuestionService.relinkContextsForSubject`; links `questions.ingestion_job_id` via Flyway `V24` without cross-module JPA coupling so post-processing mutates only the active job's questions; shifts Docling PDF parsing + LLM chunk extraction onto a bounded `ingestionTaskExecutor` so `/pdf` returns `202 Accepted` immediately; adds startup recovery for interrupted `PENDING`/`PROCESSING` jobs; and adds HTTP timeouts + balanced JSON object salvage in `LlmStructuringService`.
2. **Safe Subject Lifecycle & Validated Question Edits**: Wraps `SubjectController.updateSubject` / `deleteSubject` in `@Transactional`, returns `409 Conflict` when questions exist, deletes orphaned `question_contexts` cleanly when deleting an empty subject (with `V24` cascading historical `ingestion_jobs`), and introduces `@Valid QuestionUpdateRequest` on `PUT /api/v1/admin/questions/{id}`.
3. **High-Signal Audit Trail**: Excludes routine admin dashboard/queue/job `GET` polls from `AuditLogAspect` while auditing all mutating methods (`POST`, `PUT`, `PATCH`, `DELETE`) and sensitive export/script reads (`/slip`, `/pdf`, `/script`, `/credentials-pdf`) with 255-char endpoint truncation.
4. **Admin UI & Full CI Playwright Coverage**: Adds server-driven `PagedResponse` pagination on `/admin/results`, replaces nested `<AlertDialogTrigger><Button>` tags in `SubjectDetail` with Base UI `render={...}`, removes dark theme forcing from `SelectContent`, syncs `DraftQuestionCard` props + structured `{ type: 'image', url }` assets + ProblemDetails errors, shares consistent `\(...\)` / `\[...\]` KaTeX normalization + safe Markdown handling across `RichText`/`MathText`, and runs `auth-hardening`, `exam-kiosk-integrity`, and `ingestion-and-audit-stabilization` Playwright specs in CI.

## Technical Context

**Language/Version**: Java 17 (`backend/pom.xml`, Spring Boot 3.2.4, Spring Modulith 1.1.3) + TypeScript ~6.0.2 / React 19 (`frontend/package.json`)  
**Primary Dependencies**: Spring Web, Spring Data JPA, Spring Security, Spring Data Redis, Flyway (`V24`), Apache HTTP/RestClient, React, Tailwind CSS, Base UI (`@base-ui/react`), KaTeX, Playwright  
**Storage**: PostgreSQL 16 (Flyway `V24`), Redis (question bank cache eviction after commit)  
**Testing**: JUnit 5 + Mockito + `@SpringBootTest` Postgres/Redis integration tests (`mvn -f backend/pom.xml test` incl. `ModulithArchitectureTest`), `oxlint`, `tsc -b && vite build`, unmocked Playwright (Chromium + WebKit)  
**Target Platform**: Spring Boot + Vite SPA (local dev + GitHub Actions CI; Docker container packaging deferred to Slice 006)  
**Project Type**: Modular Monolith Web Application (`backend/` + `frontend/` + `services/docling-parser/`)  
**Performance Goals**: `POST /api/v1/admin/ingestion/pdf` responds `202` in `< 500ms`; zero `audit_logs` write amplification from polling `GET` requests  
**Constraints**: Preserve unidirectional Spring Modulith dependencies (`ingestion -> questionbank`, zero reverse imports from `questionbank` to `ingestion`); RFC 7807 `ProblemDetail` error responses; Base UI accessibility compliance (`0` nested buttons)  
**Scale/Scope**: Admin content curation (multi-page PDF banks), cohort result browsing (`> 20` candidates), CI gating across Chromium & WebKit

## Constitution Check

*GATE: Passed before Phase 0 research and re-checked after Phase 1 design.*

1. **Principle I (Modular Monolith & Asynchronous Outbox Integrity)**: PASS. `Question.ingestionJobId` is stored as a scalar `UUID` (zero Java import from `questionbank` to `ingestion`); `SubjectController.deleteSubject` deletes orphaned contexts inside `questionbank` and relies on `V24`'s `ON DELETE CASCADE` constraint on `ingestion_jobs.subject_id` rather than injecting `ingestion` repositories. Verified via `ModulithArchitectureTest`.
2. **Principle II (Zero-Trust Security, Identity & Secrets Hygiene)**: PASS. Error sanitization in `AsyncIngestionWorker` / `LlmStructuringService` remains enforced after async offload; `AuditLogAspect` keeps full audit coverage on every administrative mutation and document/script export while stopping high-frequency `GET` polling bloat.
3. **Principle III (Exam, Snapshot & Grading Integrity)**: PASS. Fixes comprehension passage pollution before questions enter `ACTIVE` bank (so frozen session snapshots stay accurate) and guarantees Redis cache eviction executes only `AFTER_COMMIT` on subject mutations/deletes.
4. **Principle IV (Shared-Kiosk Isolation & Offline-First Resilience)**: PASS. No student kiosk storage rules altered; `exam-kiosk-integrity.spec.ts` becomes permanently gated in CI.
5. **Principle V (Contract-First API & Test-First Verification)**: PASS. REST updates documented in `contracts/api-ingestion-stabilization.md` and synced to `docs/api_contract.md` during implementation; unit, integration, and live Chromium/WebKit Playwright specs cover every story.
6. **Principle VI (Code Quality, Schema & UI Discipline)**: PASS. Explicit Java imports & constructor injection per `.agents/rules/backend-standards.md`; idempotent Flyway `V24`; light-mode `bg-slate-50` `SelectContent`, valid Base UI `AlertDialogTrigger render={...}`, and safe KaTeX delimiter rendering per `.agents/rules/ui-standards.md` & `docs/frontend_architecture.md`.

## Project Structure

### Documentation (this feature)

```text
specs/005-ingestion-stabilization/
├── spec.md                                     # Feature specification (P1–P4 user stories & FRs)
├── checklists/
│   └── requirements.md                         # Specification quality checklist
├── plan.md                                     # This implementation plan
├── research.md                                 # Phase 0 architectural & bug-fix decisions
├── data-model.md                               # V24 Flyway DDL, Question/IngestionJob entities & DTOs
├── quickstart.md                               # Runnable verification & CI commands
└── contracts/
    └── api-ingestion-stabilization.md          # Ingestion, Subject delete, Question PUT, Audit & CI contracts
```

### Source Code Layout Affected

```text
backend/src/main/
├── resources/db/migration/
│   └── V24__Harden_Ingestion_Job_Links_And_Question_Provenance.sql
└── java/com/studentprep/
    ├── config/
    │   └── AsyncConfig.java                    # Bounded ingestionTaskExecutor bean
    ├── ingestion/
    │   ├── IngestionController.java            # Immediate 202 Accepted offload (read <= 10MB bytes -> async parse)
    │   ├── AsyncIngestionWorker.java           # Iterative TransactionTemplate chunking, job-scoped is_follow_up & startup recovery
    │   ├── LlmStructuringService.java          # Bounded HTTP timeouts & balanced JSON object salvage
    │   └── job/
    │       ├── IngestionJob.java               # Track updatedAt
    │       └── IngestionJobRepository.java     # Query stranded PENDING/PROCESSING jobs
    ├── questionbank/
    │   ├── Question.java                       # Scalar UUID ingestionJobId column
    │   ├── QuestionRepository.java             # findByIngestionJobIdOrderByCreatedAtAsc
    │   ├── QuestionContextRepository.java      # deleteBySubject_Id
    │   ├── QuestionService.java                # Respect is_follow_up on relinkContextsForSubject + validated update
    │   ├── QuestionController.java             # @Valid QuestionUpdateRequest on PUT /{id}
    │   ├── SubjectController.java              # @Transactional delete/update, 409 Conflict check, orphan context cleanup
    │   └── dto/QuestionUpdateRequest.java      # Validated question update payload
    └── security/
        └── AuditLogAspect.java                 # Mutation + sensitive export logging (exclude polling GETs), 255-char cap
frontend/src/
├── components/
│   ├── RichText.tsx                            # Shared KaTeX delimiter normalization + safe HTML filtering
│   └── ui/
│       ├── MathText.tsx                        # Shared KaTeX delimiter normalization (\(...\) & \[...\]) + safe HTML filtering
│       └── select.tsx                          # Light-mode-safe SelectContent styling
├── pages/admin/
│   ├── ResultsPage.tsx                         # Server-driven PagedResponse pagination controls
│   └── SubjectDetail.tsx                       # AlertDialogTrigger render={...}, ProblemDetails errors, DraftQuestionCard sync, structured assets
frontend/tests/
└── ingestion-and-audit-stabilization.spec.ts   # Slice 004 Script Audit + Slice 005 stabilization E2E
.github/workflows/
└── ci.yml                                      # Run full Playwright E2E suite (auth, kiosk, script audit & stabilization)
docs/
└── api_contract.md                             # Sync updated ingestion/subject/question/audit contracts during implementation
```

## Complexity Tracking

No Constitution violations or exceptions required.
