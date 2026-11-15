import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { test, expect, type Page } from '@playwright/test';

const CURRENT_DIR = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(CURRENT_DIR, '..', '..');

const ADMIN_ID = process.env.E2E_ADMIN_ID || 'ADMIN-001';
const ADMIN_PIN = process.env.E2E_ADMIN_PIN || 'admin123';
const SUPERVISOR_ID = process.env.E2E_SUPERVISOR_ID || 'SUPERVISOR-001';
const SUPERVISOR_PIN = process.env.E2E_SUPERVISOR_PIN || 'sup123456';

function createRunTag(): string {
  return `${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 6)}`.toUpperCase();
}

function sqlLiteral(value: string): string {
  return `'${value.replace(/'/g, "''")}'`;
}

function runPsql(sql: string): string {
  return execFileSync(
    'docker',
    [
      'exec',
      '-i',
      'studentprep-postgres',
      'psql',
      '-U',
      'postgres',
      '-d',
      'studentprep',
      '-v',
      'ON_ERROR_STOP=1',
      '-t',
      '-A',
      '-c',
      sql,
    ],
    {
      cwd: REPO_ROOT,
      encoding: 'utf8',
    }
  ).trim();
}

async function logoutViaApi(page: Page): Promise<void> {
  if (page.isClosed()) return;
  try {
    await page.evaluate(async () => {
      const token = localStorage.getItem('token');
      try {
        await fetch('/api/v1/auth/logout', {
          method: 'POST',
          credentials: 'include',
          headers: token ? { Authorization: `Bearer ${token}` } : {},
        });
      } finally {
        localStorage.removeItem('token');
      }
    });
  } catch {
    // Ignore cleanup errors when page has already navigated or closed.
  }
}

async function loginStaff(page: Page, identifier: string, pin: string): Promise<string> {
  await page.goto('/admin');
  await expect(page.getByRole('heading', { name: 'Admin Login' })).toBeVisible();
  await page.locator('#admin-identifier').fill(identifier);
  await page.locator('#admin-password').fill(pin);

  const loginResponsePromise = page.waitForResponse(
    (res) => res.url().includes('/api/v1/auth/login') && res.request().method() === 'POST'
  );
  await page.getByRole('button', { name: 'Login', exact: true }).click();
  const loginRes = await loginResponsePromise;
  expect(loginRes.status()).toBe(200);
  await expect(page.getByRole('link', { name: 'Students' })).toBeVisible({ timeout: 15000 });

  const token = await page.evaluate(() => localStorage.getItem('token'));
  expect(token).toBeTruthy();
  return token as string;
}

async function loginStudent(page: Page, registrationNumber: string, pin: string): Promise<string> {
  await page.goto('/');
  await page.locator('#student-identifier').fill(registrationNumber);
  await page.locator('#student-pin').fill(pin);

  const loginResponsePromise = page.waitForResponse(
    (res) => res.url().includes('/api/v1/auth/login') && res.request().method() === 'POST'
  );
  await page.getByRole('button', { name: 'Login', exact: true }).click();
  const loginRes = await loginResponsePromise;
  expect(loginRes.status()).toBe(200);

  const token = await page.evaluate(() => localStorage.getItem('token'));
  expect(token).toBeTruthy();
  return token as string;
}

async function createSubjectViaUi(page: Page, subjectName: string): Promise<string> {
  await page.goto('/admin/subjects');
  await expect(page.getByRole('heading', { name: 'Manage Subjects' })).toBeVisible({
    timeout: 15000,
  });

  await page.getByPlaceholder('New Subject Name').fill(subjectName);
  const createResPromise = page.waitForResponse(
    (res) => res.url().endsWith('/api/v1/admin/subjects') && res.request().method() === 'POST'
  );
  await page.getByRole('button', { name: 'Create', exact: true }).click();
  const createRes = await createResPromise;
  expect(createRes.status()).toBe(200);
  const payload = (await createRes.json()) as { data?: { id?: string } };
  const subjectId = payload.data?.id;
  expect(subjectId).toBeTruthy();
  await expect(page.getByRole('link', { name: subjectName })).toBeVisible({ timeout: 10000 });
  return subjectId as string;
}

async function createStudentForSingleSubjectViaUi(
  page: Page,
  candidateName: string,
  subjectName: string
): Promise<{ id?: string; registrationNumber: string; oneTimePin: string }> {
  await page.goto('/admin/students');
  await expect(page.getByRole('heading', { name: 'Students', exact: true })).toBeVisible({
    timeout: 15000,
  });

  await page.getByRole('button', { name: 'Register Student' }).click();
  await expect(page.getByRole('heading', { name: 'Register Student' })).toBeVisible();
  await page.locator('#create-student-name').fill(candidateName);

  const targetLabel = page.locator('form label', { hasText: subjectName }).first();
  await expect(targetLabel).toBeVisible({ timeout: 10000 });
  const targetCheckbox = targetLabel.locator('input[type="checkbox"]');
  if (!(await targetCheckbox.isChecked())) {
    await targetCheckbox.check();
  }

  const createResPromise = page.waitForResponse(
    (res) => res.url().endsWith('/api/v1/admin/students') && res.request().method() === 'POST'
  );
  await page.getByRole('button', { name: 'Create Student' }).click();
  const createRes = await createResPromise;
  expect(createRes.status()).toBe(201);
  const createdJson = (await createRes.json()) as { data?: { id?: string } };

  const slip = page.getByTestId('one-time-pin-slip');
  await expect(slip).toBeVisible({ timeout: 15000 });

  const oneTimePin = (await slip.getByTestId('revealed-one-time-pin').innerText()).trim();
  const registrationNumber = (
    await slip.locator('div.font-mono.font-bold.text-slate-900').first().innerText()
  ).trim();

  await slip.getByRole('button', { name: 'Done', exact: true }).click();
  await expect(slip).toBeHidden();

  return {
    id: createdJson.data?.id,
    registrationNumber,
    oneTimePin,
  };
}

async function deleteSubjectResources(subjectId: string, adminToken: string): Promise<void> {
  await fetch(`http://localhost:8080/api/v1/admin/questions/drafts/bulk?subjectId=${subjectId}`, {
    method: 'DELETE',
    headers: { Authorization: `Bearer ${adminToken}` },
  }).catch(() => {});
  await fetch(`http://localhost:8080/api/v1/admin/questions/active/bulk?subjectId=${subjectId}`, {
    method: 'DELETE',
    headers: { Authorization: `Bearer ${adminToken}` },
  }).catch(() => {});
  runPsql(`
    DELETE FROM questions WHERE subject_id = ${sqlLiteral(subjectId)};
    DELETE FROM question_contexts WHERE subject_id = ${sqlLiteral(subjectId)};
    DELETE FROM ingestion_jobs WHERE subject_id = ${sqlLiteral(subjectId)};
    DELETE FROM subjects WHERE id = ${sqlLiteral(subjectId)};
  `);
}

async function waitForResultRowAndOpenInspect(
  page: Page,
  registrationNumber: string,
  expectedReceiptCode: string
): Promise<string> {
  const initialResultsPromise = page.waitForResponse(
    (res) =>
      res.url().includes('/api/v1/admin/analytics/results') && res.request().method() === 'GET'
  );
  await page.goto('/admin/results');
  await expect(page.getByRole('heading', { name: 'Exam Results & Moderation' })).toBeVisible({
    timeout: 15000,
  });
  const initialRes = await initialResultsPromise;
  expect(initialRes.status()).toBe(200);
  await expect(page.locator('tbody tr').first()).toBeVisible({ timeout: 15000 });

  for (let pageGuard = 0; pageGuard < 25; pageGuard += 1) {
    await expect(page.getByText(new RegExp(`^Page ${pageGuard + 1} of \\d+`))).toBeVisible({
      timeout: 10000,
    });
    const row = page.locator('tbody tr', { hasText: registrationNumber }).first();
    if ((await row.count()) > 0) {
      await expect(row).toBeVisible();
      if (expectedReceiptCode) {
        await expect(row).toContainText(expectedReceiptCode);
      }
      const actionBtn = row.locator('[data-testid^="result-actions-"]').first();
      const testId = await actionBtn.getAttribute('data-testid');
      expect(testId).toBeTruthy();
      await actionBtn.click();

      const inspectItem = page.locator('[data-testid^="inspect-script-"]').first();
      await expect(inspectItem).toBeVisible({ timeout: 5000 });
      await inspectItem.click();
      return testId as string;
    }

    const nextBtn = page.getByRole('button', { name: 'Next', exact: true });
    if ((await nextBtn.count()) === 0 || (await nextBtn.isDisabled())) {
      break;
    }
    const nextResPromise = page.waitForResponse(
      (res) =>
        res.url().includes('/api/v1/admin/analytics/results') && res.request().method() === 'GET'
    );
    await nextBtn.click();
    const nextRes = await nextResPromise;
    expect(nextRes.status()).toBe(200);
  }

  throw new Error(`Could not find result row for ${registrationNumber} on /admin/results`);
}

test.describe.serial('Ingestion, Lifecycle, Audit & Script Verification E2E (Unmocked)', () => {
  test.beforeEach(({ browserName }) => {
    if (browserName === 'webkit') {
      test.slow();
    }
  });

  test('T025.1: Admin & Supervisor Exam Script Audit modal (25/page, receipt, PDF) + Student 403 isolation', async ({
    page,
  }) => {
    const runTag = createRunTag();
    const subjectName = `AuditSub_${runTag}`;
    const candidateName = `AuditCandidate ${runTag}`;
    let subjectId = '';
    let studentReg = '';
    let adminToken = '';

    try {
      adminToken = await loginStaff(page, ADMIN_ID, ADMIN_PIN);
      subjectId = await createSubjectViaUi(page, subjectName);

      // Seed 28 draft questions for the subject, then approve via UI so Redis cache evicts natively.
      const insertStatements: string[] = [];
      for (let i = 1; i <= 28; i += 1) {
        const qId = randomUUID();
        const contentJson = JSON.stringify({
          type: 'MCQ',
          questionNumber: String(i),
          text: `Audit question ${i} (${runTag}): evaluate $x + ${i}$`,
          options: {
            A: `Option A-${i}`,
            B: `Option B-${i}`,
            C: `Option C-${i}`,
            D: `Option D-${i}`,
          },
          correctOption: 'A',
        });
        insertStatements.push(
          `INSERT INTO questions (id, subject_id, topic, status, content, created_at) VALUES (${sqlLiteral(qId)}, ${sqlLiteral(subjectId)}, 'Algebra', 'DRAFT', ${sqlLiteral(contentJson)}::jsonb, NOW() + (${i} * INTERVAL '1 second'));`
        );
      }
      runPsql(insertStatements.join('\n'));

      await page.goto(`/admin/subjects/${subjectId}`);
      await expect(page.getByRole('button', { name: /Pending Review \(28\)/i })).toBeVisible({
        timeout: 15000,
      });
      await page.getByRole('button', { name: /Pending Review \(28\)/i }).click();

      await page.getByRole('button', { name: /Approve All \(28\)/i }).click();
      const approveResPromise = page.waitForResponse(
        (res) =>
          res.url().includes('/api/v1/admin/questions/drafts/bulk-approve') &&
          res.request().method() === 'POST'
      );
      await page
        .locator('[role="alertdialog"]')
        .getByRole('button', { name: 'Approve All' })
        .click();
      const approveRes = await approveResPromise;
      expect(approveRes.status()).toBe(200);
      await expect(page.getByRole('button', { name: /Active Bank \(28\)/i })).toBeVisible({
        timeout: 15000,
      });

      const student = await createStudentForSingleSubjectViaUi(page, candidateName, subjectName);
      studentReg = student.registrationNumber;
      await logoutViaApi(page);

      // Student takes and submits the 28-question exam via UI.
      const studentToken = await loginStudent(page, student.registrationNumber, student.oneTimePin);
      await expect(page.getByText('Examination Portal', { exact: true })).toBeVisible({
        timeout: 15000,
      });
      await page.getByRole('button', { name: /Start Examination/i }).click();
      await expect(page.locator('header')).toContainText(student.registrationNumber, {
        timeout: 15000,
      });

      const firstOption = page.getByRole('radio').first();
      await expect(firstOption).toBeVisible({ timeout: 10000 });
      await firstOption.click();

      await page.getByRole('button', { name: 'Submit Final' }).click();
      const submitResPromise = page.waitForResponse(
        (res) => res.url().includes('/api/v1/exams/active/submit') && res.request().method() === 'POST'
      );
      await page.getByRole('button', { name: 'Submit', exact: true }).click();
      const submitRes = await submitResPromise;
      expect(submitRes.status()).toBe(200);
      await expect(page).toHaveURL(/.*\/result/, { timeout: 15000 });

      const studentResultsRes = await page.request.get('/api/v1/students/me/results', {
        headers: { Authorization: `Bearer ${studentToken}` },
      });
      expect(studentResultsRes.status()).toBe(200);
      const studentResultsBody = (await studentResultsRes.json()) as {
        data?: Array<{ examSessionId?: string; answerReceiptCode?: string }>;
      };
      const submittedSessionId = studentResultsBody.data?.[0]?.examSessionId;
      const expectedReceipt = studentResultsBody.data?.[0]?.answerReceiptCode || '';
      expect(submittedSessionId).toBeTruthy();

      // Verify Student role receives 403 Forbidden on admin script endpoints.
      const studentScriptJsonRes = await page.request.get(
        `/api/v1/admin/exams/sessions/${submittedSessionId}/script`,
        { headers: { Authorization: `Bearer ${studentToken}` } }
      );
      expect(studentScriptJsonRes.status()).toBe(403);

      const studentScriptPdfRes = await page.request.get(
        `/api/v1/admin/exams/sessions/${submittedSessionId}/script/pdf`,
        { headers: { Authorization: `Bearer ${studentToken}` } }
      );
      expect(studentScriptPdfRes.status()).toBe(403);
      await logoutViaApi(page);

      // Admin inspects script modal, verifies receipt code, 25/page pagination, and downloads PDF.
      adminToken = await loginStaff(page, ADMIN_ID, ADMIN_PIN);
      await waitForResultRowAndOpenInspect(page, student.registrationNumber, expectedReceipt);

      const modal = page.getByTestId('exam-script-audit-modal');
      await expect(modal).toBeVisible({ timeout: 15000 });
      const modalReceipt = (await page.getByTestId('audit-answer-receipt-code').innerText()).trim();
      expect(modalReceipt).toBeTruthy();
      if (expectedReceipt) {
        expect(modalReceipt).toBe(expectedReceipt);
      }

      await expect(modal).toContainText('1–25 of 28');
      await expect(modal).toContainText('Page 1 of 2');
      await modal.getByRole('button', { name: /Next 25/i }).click();
      await expect(modal).toContainText('26–28 of 28');
      await expect(modal).toContainText('Page 2 of 2');
      await modal.getByRole('button', { name: /Previous 25/i }).click();
      await expect(modal).toContainText('Page 1 of 2');

      const adminDownloadPromise = page.waitForEvent('download');
      await page.getByTestId('modal-download-script-pdf').click();
      const adminDownload = await adminDownloadPromise;
      expect(adminDownload.suggestedFilename()).toContain('.pdf');
      const adminPdfStream = await adminDownload.createReadStream();
      const adminChunks: Buffer[] = [];
      for await (const chunk of adminPdfStream!) {
        adminChunks.push(Buffer.from(chunk));
      }
      expect(Buffer.concat(adminChunks).subarray(0, 5).toString('ascii')).toBe('%PDF-');

      await modal.getByRole('button', { name: 'Close', exact: true }).click();
      await expect(modal).toBeHidden();
      await logoutViaApi(page);

      // Supervisor can inspect script and download Script (PDF) cleanly.
      await loginStaff(page, SUPERVISOR_ID, SUPERVISOR_PIN);
      await waitForResultRowAndOpenInspect(page, student.registrationNumber, modalReceipt);
      const supModal = page.getByTestId('exam-script-audit-modal');
      await expect(supModal).toBeVisible({ timeout: 15000 });
      await expect(page.getByTestId('audit-answer-receipt-code')).toHaveText(modalReceipt);

      const supDownloadPromise = page.waitForEvent('download');
      await page.getByTestId('modal-download-script-pdf').click();
      const supDownload = await supDownloadPromise;
      const supPdfStream = await supDownload.createReadStream();
      const supChunks: Buffer[] = [];
      for await (const chunk of supPdfStream!) {
        supChunks.push(Buffer.from(chunk));
      }
      expect(Buffer.concat(supChunks).subarray(0, 5).toString('ascii')).toBe('%PDF-');
      await supModal.getByRole('button', { name: 'Close', exact: true }).click();
    } finally {
      await logoutViaApi(page);
      if (studentReg) {
        runPsql(`
          DELETE FROM students WHERE registration_number = ${sqlLiteral(studentReg)};
          DELETE FROM users WHERE identifier = ${sqlLiteral(studentReg)};
        `);
      }
      if (subjectId && adminToken) {
        await deleteSubjectResources(subjectId, adminToken);
      }
    }
  });

  test('T025.2: /admin/results server-driven pagination traverses multiple pages across > 20 results', async ({
    page,
  }) => {
    const runTag = createRunTag();
    const seededStudentRegs: string[] = [];

    try {
      // Ensure > 20 graded exam_results exist in PostgreSQL.
      const statements: string[] = [];
      for (let i = 1; i <= 21; i += 1) {
        const studentId = randomUUID();
        const sessionId = randomUUID();
        const resultId = randomUUID();
        const regNo = `PG${runTag.slice(-4)}${String(i).padStart(2, '0')}`;
        seededStudentRegs.push(regNo);

        statements.push(`
          INSERT INTO students (id, name, registration_number, pin_hash, pin_reset_required)
          VALUES (${sqlLiteral(studentId)}, ${sqlLiteral(`Paginated Candidate ${i} ${runTag}`)}, ${sqlLiteral(regNo)}, '$argon2id$v=19$m=16384,t=2,p=1$dummy$dummy', false);
          INSERT INTO exam_sessions (id, user_id, start_time, end_time, expected_end_time, status, state_payload, shuffle_seed, snapshot_payload, superseded)
          VALUES (${sqlLiteral(sessionId)}, ${sqlLiteral(studentId)}, NOW() - INTERVAL '30 minutes', NOW() - INTERVAL '5 minutes', NOW() + INTERVAL '90 minutes', 'SUBMITTED', '{"answers":{}}'::jsonb, 12345, '{"questions":[]}'::jsonb, false);
          INSERT INTO exam_results (id, student_id, exam_session_id, total_score, max_score, topic_breakdown, graded_at, moderation_status)
          VALUES (${sqlLiteral(resultId)}, ${sqlLiteral(studentId)}, ${sqlLiteral(sessionId)}, ${10 + (i % 10)}, 20, '{}'::jsonb, NOW(), 'ELIGIBLE');
        `);
      }
      runPsql(statements.join('\n'));

      await loginStaff(page, ADMIN_ID, ADMIN_PIN);

      const page0ResPromise = page.waitForResponse(
        (res) =>
          res.url().includes('/api/v1/admin/analytics/results') &&
          res.url().includes('page=0') &&
          res.url().includes('size=20')
      );
      await page.goto('/admin/results');
      const page0Res = await page0ResPromise;
      expect(page0Res.status()).toBe(200);
      const page0Json = (await page0Res.json()) as {
        meta?: { page: number; size: number; totalElements: number; totalPages: number };
      };
      expect(page0Json.meta?.totalElements).toBeGreaterThanOrEqual(21);
      expect(page0Json.meta?.totalPages).toBeGreaterThanOrEqual(2);

      await expect(page.locator('tbody tr')).toHaveCount(20, { timeout: 15000 });
      await expect(page.getByText(/^Page 1 of \d+ \(\d+ results?\)$/)).toBeVisible();

      const prevBtn = page.getByRole('button', { name: 'Previous', exact: true });
      const nextBtn = page.getByRole('button', { name: 'Next', exact: true });
      await expect(prevBtn).toBeDisabled();
      await expect(nextBtn).toBeEnabled();

      const page1ResPromise = page.waitForResponse(
        (res) =>
          res.url().includes('/api/v1/admin/analytics/results') &&
          res.url().includes('page=1') &&
          res.url().includes('size=20')
      );
      await nextBtn.click();
      const page1Res = await page1ResPromise;
      expect(page1Res.status()).toBe(200);

      await expect(page.getByText(/^Page 2 of \d+ \(\d+ results?\)$/)).toBeVisible();
      await expect(prevBtn).toBeEnabled();
      expect(await page.locator('tbody tr').count()).toBeGreaterThan(0);

      const backPage0Promise = page.waitForResponse(
        (res) =>
          res.url().includes('/api/v1/admin/analytics/results') &&
          res.url().includes('page=0') &&
          res.url().includes('size=20')
      );
      await prevBtn.click();
      await backPage0Promise;
      await expect(page.getByText(/^Page 1 of \d+ \(\d+ results?\)$/)).toBeVisible();
    } finally {
      await logoutViaApi(page);
      if (seededStudentRegs.length > 0) {
        const regList = seededStudentRegs.map(sqlLiteral).join(',');
        runPsql(`DELETE FROM students WHERE registration_number IN (${regList});`);
      }
    }
  });

  test('T025.3: SubjectDetail accessibility (zero nested buttons), light-mode Select, KaTeX, and draft PUT edit', async ({
    page,
  }) => {
    const runTag = createRunTag();
    const subjectName = `UiSub_${runTag}`;
    const draftQuestionId = randomUUID();
    let subjectId = '';
    let adminToken = '';

    try {
      adminToken = await loginStaff(page, ADMIN_ID, ADMIN_PIN);
      subjectId = await createSubjectViaUi(page, subjectName);

      // Verify zero nested interactive buttons on SubjectList too.
      expect(await page.locator('button button').count()).toBe(0);

      const mathContent = JSON.stringify({
        type: 'MCQ_LATEX',
        questionNumber: '1',
        text: 'Inline \\( x^2 + 1 \\) and display \\[ \\frac{a}{b} \\]',
        options: {
          A: 'Alpha',
          B: 'Beta',
        },
        correctOption: 'A',
      });
      runPsql(`
        INSERT INTO questions (id, subject_id, topic, status, content)
        VALUES (${sqlLiteral(draftQuestionId)}, ${sqlLiteral(subjectId)}, 'Calculus', 'DRAFT', ${sqlLiteral(mathContent)}::jsonb);
      `);

      await page.goto(`/admin/subjects/${subjectId}`);
      await expect(page.getByRole('heading', { name: subjectName })).toBeVisible({
        timeout: 15000,
      });

      await page.getByRole('button', { name: /Pending Review \(1\)/i }).click();
      await expect(page.getByRole('button', { name: 'Edit', exact: true })).toBeVisible({
        timeout: 10000,
      });

      // Zero nested buttons anywhere on SubjectDetail, including inside AlertDialogTrigger.
      expect(await page.locator('button button').count()).toBe(0);
      const triggers = page.locator('[data-slot="alert-dialog-trigger"]');
      const triggerCount = await triggers.count();
      expect(triggerCount).toBeGreaterThan(0);
      for (let i = 0; i < triggerCount; i += 1) {
        expect(await triggers.nth(i).locator('button').count()).toBe(0);
      }

      // KaTeX compiled both \(...\) and \[...\] expressions into .katex nodes.
      expect(await page.locator('.katex').count()).toBeGreaterThanOrEqual(2);

      // Edit draft text and save via PUT /api/v1/admin/questions/{id}.
      await page.getByRole('button', { name: 'Edit', exact: true }).click();
      const updatedPrompt = `Updated prompt ${runTag}: \\( y = mx + c \\)`;
      await page.getByLabel('Question Text').fill(updatedPrompt);

      const savePutPromise = page.waitForResponse(
        (res) =>
          res.url().includes(`/api/v1/admin/questions/${draftQuestionId}`) &&
          res.request().method() === 'PUT'
      );
      await page.getByRole('button', { name: 'Save', exact: true }).click();
      const savePutRes = await savePutPromise;
      expect(savePutRes.status()).toBe(200);

      // Open Select popup, verify light-mode styling (no dark surface class), and update correctOption via PUT.
      const selectTrigger = page.locator('[data-slot="select-trigger"]').first();
      await selectTrigger.click();
      const selectPopup = page.locator('[data-slot="select-content"]').first();
      await expect(selectPopup).toBeVisible({ timeout: 5000 });
      const popupStyles = await selectPopup.evaluate((el) => {
        const computed = window.getComputedStyle(el);
        return {
          hasDarkClass: el.className.split(/\s+/).includes('dark'),
          backgroundColor: computed.backgroundColor,
          color: computed.color,
        };
      });
      expect(popupStyles.hasDarkClass).toBe(false);
      expect(popupStyles.backgroundColor).toBe('rgb(255, 255, 255)');
      expect(popupStyles.color).not.toBe('rgb(255, 255, 255)');

      const optionPutPromise = page.waitForResponse(
        (res) =>
          res.url().includes(`/api/v1/admin/questions/${draftQuestionId}`) &&
          res.request().method() === 'PUT'
      );
      await selectPopup.getByRole('option', { name: 'B', exact: true }).click();
      const optionPutRes = await optionPutPromise;
      expect(optionPutRes.status()).toBe(200);

      // Reload and confirm persistence.
      await page.reload();
      await page.getByRole('button', { name: /Pending Review \(1\)/i }).click();
      await expect(page.getByText(`Updated prompt ${runTag}:`, { exact: false })).toBeVisible({
        timeout: 15000,
      });
      await expect(selectTrigger).toContainText('B');
    } finally {
      await logoutViaApi(page);
      if (subjectId && adminToken) {
        await deleteSubjectResources(subjectId, adminToken);
      }
    }
  });

  test('T025.4: Safe Subject deletion returns 409 with questions and 200 when emptied despite orphan context/failed job', async ({
    page,
  }) => {
    const runTag = createRunTag();
    const subjectName = `DelSub_${runTag}`;
    const qId = randomUUID();
    const ctxId = randomUUID();
    const jobId = randomUUID();
    let subjectId = '';
    let adminToken = '';

    try {
      adminToken = await loginStaff(page, ADMIN_ID, ADMIN_PIN);
      subjectId = await createSubjectViaUi(page, subjectName);

      // Seed 1 draft question + 1 orphaned question_contexts row + 1 FAILED ingestion_jobs row.
      const qContent = JSON.stringify({
        type: 'MCQ',
        text: 'Blocking question',
        options: { A: '1', B: '2' },
        correctOption: 'A',
      });
      runPsql(`
        INSERT INTO questions (id, subject_id, topic, status, content)
        VALUES (${sqlLiteral(qId)}, ${sqlLiteral(subjectId)}, 'Core', 'DRAFT', ${sqlLiteral(qContent)}::jsonb);
        INSERT INTO question_contexts (id, subject_id, passage)
        VALUES (${sqlLiteral(ctxId)}, ${sqlLiteral(subjectId)}, 'Orphaned passage text');
        INSERT INTO ingestion_jobs (id, subject_id, status, error_message)
        VALUES (${sqlLiteral(jobId)}, ${sqlLiteral(subjectId)}, 'FAILED', 'Simulated failed job');
      `);

      await page.reload();
      const subjectRow = page.locator('div.bg-white', { hasText: subjectName }).first();
      await expect(subjectRow).toBeVisible({ timeout: 15000 });

      // Attempt delete while question exists -> expect 409 Conflict ProblemDetail.
      await subjectRow.locator('[data-slot="alert-dialog-trigger"]').click();
      const conflictDeletePromise = page.waitForResponse(
        (res) =>
          res.url().includes(`/api/v1/admin/subjects/${subjectId}`) &&
          res.request().method() === 'DELETE'
      );
      await page.locator('[role="alertdialog"]').getByRole('button', { name: 'Delete' }).click();
      const conflictRes = await conflictDeletePromise;
      expect(conflictRes.status()).toBe(409);
      const conflictJson = (await conflictRes.json()) as { detail?: string };
      expect(conflictJson.detail).toMatch(/questions remain/i);
      await expect(page.getByText(/questions remain/i)).toBeVisible({ timeout: 5000 });
      await expect(page.getByRole('link', { name: subjectName })).toBeVisible();

      // Delete the blocking question via API, leaving orphan context + FAILED ingestion job.
      const delQRes = await page.request.delete(`/api/v1/admin/questions/${qId}`, {
        headers: { Authorization: `Bearer ${adminToken}` },
      });
      expect(delQRes.status()).toBe(200);

      // Retry subject delete -> expect 200 OK cleanly.
      await subjectRow.locator('[data-slot="alert-dialog-trigger"]').click();
      const okDeletePromise = page.waitForResponse(
        (res) =>
          res.url().includes(`/api/v1/admin/subjects/${subjectId}`) &&
          res.request().method() === 'DELETE'
      );
      await page.locator('[role="alertdialog"]').getByRole('button', { name: 'Delete' }).click();
      const okRes = await okDeletePromise;
      expect(okRes.status()).toBe(200);
      await expect(page.getByRole('link', { name: subjectName })).toHaveCount(0);

      expect(runPsql(`SELECT count(*) FROM subjects WHERE id = ${sqlLiteral(subjectId)};`)).toBe('0');
      expect(
        runPsql(`SELECT count(*) FROM question_contexts WHERE id = ${sqlLiteral(ctxId)};`)
      ).toBe('0');
      expect(runPsql(`SELECT count(*) FROM ingestion_jobs WHERE id = ${sqlLiteral(jobId)};`)).toBe(
        '0'
      );
      subjectId = '';
    } finally {
      await logoutViaApi(page);
      if (subjectId && adminToken) {
        await deleteSubjectResources(subjectId, adminToken);
      }
    }
  });

  test('T025.5: Invalid PDF validation, async 202 ingestion polling, and zero polling GET entries in audit_logs', async ({
    page,
  }) => {
    const runTag = createRunTag();
    const subjectName = `IngestSub_${runTag}`;
    let subjectId = '';
    let createdJobId = '';
    let adminToken = '';

    try {
      adminToken = await loginStaff(page, ADMIN_ID, ADMIN_PIN);
      subjectId = await createSubjectViaUi(page, subjectName);

      const maxAuditIdBefore = Number(runPsql('SELECT COALESCE(MAX(id), 0) FROM audit_logs;') || '0');

      await page.goto(`/admin/subjects/${subjectId}`);
      await expect(page.getByRole('heading', { name: subjectName })).toBeVisible({
        timeout: 15000,
      });

      const fileInput = page.locator('input[type="file"][accept=".pdf"]');

      // 1. Disguised non-PDF payload is rejected with 400 ProblemDetail surfaced in toast.
      await fileInput.setInputFiles({
        name: 'spoofed.pdf',
        mimeType: 'application/pdf',
        buffer: Buffer.from('Plain text pretending to be a PDF file', 'utf8'),
      });
      const invalidResPromise = page.waitForResponse(
        (res) =>
          res.url().includes('/api/v1/admin/ingestion/pdf') && res.request().method() === 'POST'
      );
      await page.getByRole('button', { name: 'Start Extraction', exact: true }).click();
      const invalidRes = await invalidResPromise;
      expect(invalidRes.status()).toBe(400);
      const invalidJson = (await invalidRes.json()) as { detail?: string };
      expect(invalidJson.detail).toBeTruthy();
      await expect(page.getByText(invalidJson.detail as string)).toBeVisible({ timeout: 5000 });

      // 2. Valid PDF header returns 202 Accepted immediately and UI polls GET /jobs/{jobId}.
      await fileInput.setInputFiles({
        name: 'valid_sample.pdf',
        mimeType: 'application/pdf',
        buffer: Buffer.from('%PDF-1.4\n1 0 obj\n<<>>\nendobj\n%%EOF', 'utf8'),
      });
      const acceptedResPromise = page.waitForResponse(
        (res) =>
          res.url().includes('/api/v1/admin/ingestion/pdf') && res.request().method() === 'POST'
      );
      await page.getByRole('button', { name: 'Start Extraction', exact: true }).click();
      const acceptedRes = await acceptedResPromise;
      expect(acceptedRes.status()).toBe(202);
      const acceptedJson = (await acceptedRes.json()) as {
        data?: { jobId?: string; status?: string };
      };
      createdJobId = acceptedJson.data?.jobId || '';
      expect(createdJobId).toBeTruthy();
      expect(['PENDING', 'PROCESSING']).toContain(acceptedJson.data?.status);

      const jobPollResPromise = page.waitForResponse(
        (res) =>
          res.url().includes(`/api/v1/admin/ingestion/jobs/${createdJobId}`) &&
          res.request().method() === 'GET'
      );
      const jobPollRes = await jobPollResPromise;
      expect(jobPollRes.status()).toBe(200);

      // Issue additional routine polling GET requests that MUST NOT write audit_logs rows.
      const pollUrls = [
        '/api/v1/admin/analytics/dashboard',
        '/api/v1/admin/analytics/results?page=0&size=20',
        '/api/v1/admin/analytics/flagged-sessions',
        '/api/v1/admin/subjects',
        `/api/v1/admin/questions?status=DRAFT&subjectId=${subjectId}`,
        `/api/v1/admin/ingestion/jobs/${createdJobId}`,
      ];
      for (const url of pollUrls) {
        const res = await page.request.get(url, {
          headers: { Authorization: `Bearer ${adminToken}` },
        });
        expect(res.status()).toBe(200);
      }

      const auditRowsRaw = runPsql(
        `SELECT action || '|' || endpoint FROM audit_logs WHERE id > ${maxAuditIdBefore} ORDER BY id ASC;`
      );
      const auditLines = auditRowsRaw ? auditRowsRaw.split(/\r?\n/).filter(Boolean) : [];

      const routinePollingActions = new Set([
        'getDashboard',
        'getResults',
        'getFlaggedSessions',
        'getAllSubjectsAdmin',
        'getSubjectById',
        'getQuestionsByStatus',
        'getJobStatus',
      ]);
      const routinePollingAudits = auditLines.filter((line) => {
        const [actionName] = line.split('|');
        return routinePollingActions.has(actionName);
      });
      expect(routinePollingAudits).toHaveLength(0);
      expect(
        auditLines.some(
          (line) =>
            line.startsWith('ingestPdf|') && line.includes('/api/v1/admin/ingestion/pdf')
        )
      ).toBe(true);
    } finally {
      await logoutViaApi(page);
      if (createdJobId) {
        // Wait briefly for background worker to settle before deleting subject.
        for (let attempt = 0; attempt < 20; attempt += 1) {
          const st = runPsql(
            `SELECT status FROM ingestion_jobs WHERE id = ${sqlLiteral(createdJobId)};`
          );
          if (!st || st === 'COMPLETED' || st === 'FAILED') {
            break;
          }
          await page.waitForTimeout(250);
        }
      }
      if (subjectId && adminToken) {
        await deleteSubjectResources(subjectId, adminToken);
      }
    }
  });
});
