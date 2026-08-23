# StudentPrep E2E Testing Strategy (Playwright)

Because the CBT exam platform relies heavily on browser-native APIs (IndexedDB, Service Workers, `visibilitychange`), standard unit tests (like Jest/Vitest) are insufficient. The `devops-architect` must configure **Playwright** to run End-to-End (E2E) tests in the CI/CD pipeline.

## 1. Simulating Offline Resilience (IndexedDB Queuing)
The CI pipeline must prove that if the internet drops, the student's exam answers are safely persisted locally.

**Playwright Test Flow:**
1.  **Login & Start:** The test navigates to the exam, authenticates, and starts the timer.
2.  **Answer Initial Questions:** Click Options A and B for the first two questions (verifying the API syncs normally).
3.  **Simulate Network Drop:** Use Playwright's `context.setOffline(true)` or network interception to sever the connection to the backend `/api/v1/exams/*`.
4.  **Answer Offline Questions:** Click Options C and D for questions 3 and 4.
5.  **Assert IndexedDB State:** The test must explicitly execute Javascript in the page context (`page.evaluate()`) to query Dexie.js and assert that answers 3 and 4 are sitting in the outbound queue.
6.  **Reconnect & Verify Flush:** Restore network connectivity (`context.setOffline(false)`). Assert that the Background Sync API or Web Worker flushes the queue, and verify a mock backend receives the payload.

## 2. Simulating the Anti-Cheating Engine
The system must automatically submit the exam if the student clicks away from the tab 3 times.

**Playwright Test Flow:**
1.  **Start Exam:** Authenticate and load the exam payload.
2.  **Trigger Tab Switch 1:** Use Playwright to open a new tab (`context.newPage()`), forcing the exam tab to fire a `blur` and `visibilitychange` event.
3.  **Assert Warning:** Return to the exam tab and assert that a UI Warning Modal appears ("You have left the exam window. 2 warnings remaining.").
4.  **Trigger Tab Switch 3:** Repeat the blur process until the counter hits 3.
5.  **Assert Auto-Submission:** Assert that the exam is immediately locked, the Redux store state changes to `SUBMITTED`, and a final API payload is fired to the backend with the flag `FLAGGED_TAB_SWITCH`.

## 3. CI/CD Integration Requirements
*   These tests must run on headless Chromium and WebKit.
*   The `devops-architect` must configure the GitHub Actions (or GitLab CI) workflow to spin up the Spring Boot backend and PostgreSQL database using `docker-compose` *before* running the Playwright test suite against `http://localhost:5173`.
