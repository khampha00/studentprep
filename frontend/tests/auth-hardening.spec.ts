import { inflateSync } from 'node:zlib';
import { test, expect, type Download, type Page } from '@playwright/test';

const ADMIN_ID = process.env.E2E_ADMIN_ID || 'ADMIN-001';
const ADMIN_PIN = process.env.E2E_ADMIN_PIN || 'admin123';
const SUPERVISOR_ID = process.env.E2E_SUPERVISOR_ID || 'SUPERVISOR-001';
const SUPERVISOR_PIN = process.env.E2E_SUPERVISOR_PIN || 'sup123456';

function createRunTag(): string {
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 6)}`.toUpperCase();
}

async function readDownloadBuffer(download: Download): Promise<Buffer> {
  const stream = await download.createReadStream();
  expect(stream).toBeTruthy();
  const chunks: Buffer[] = [];
  for await (const chunk of stream!) {
    chunks.push(Buffer.from(chunk));
  }
  return Buffer.concat(chunks);
}

function extractOpenPdfText(pdfBuffer: Buffer): string {
  expect(pdfBuffer.subarray(0, 5).toString('ascii')).toBe('%PDF-');
  const raw = pdfBuffer.toString('latin1');
  const sections: string[] = [raw];
  const streamRegex = /stream(?:\r\n|\n|\r)([\s\S]*?)endstream/g;
  for (const match of raw.matchAll(streamRegex)) {
    const candidate = match[1].replace(/(?:\r\n|\n|\r)$/, '');
    const body = Buffer.from(candidate, 'latin1');
    try {
      sections.push(inflateSync(body).toString('latin1'));
    } catch {
      sections.push(candidate);
    }
  }
  const combined = sections.join('\n');
  const tokens: string[] = [];
  const parenRegex = /\(([^()\\]*(?:\\.[^()\\]*)*)\)/g;
  for (const match of combined.matchAll(parenRegex)) {
    tokens.push(
      match[1]
        .replace(/\\([()\\])/g, '$1')
        .replace(/\\n/g, '\n')
        .replace(/\\r/g, '\r')
    );
  }
  return `${combined}\n${tokens.join(' ')}\n${tokens.join('')}`;
}

function decodeRoleFromJwt(token: string | null): string | null {
  if (!token) return null;
  const parts = token.split('.');
  if (parts.length !== 3 || !parts[1]) return null;
  try {
    const base64 = parts[1].replace(/-/g, '+').replace(/_/g, '/');
    const padded = base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '=');
    const payload = JSON.parse(Buffer.from(padded, 'base64').toString('utf8')) as {
      role?: unknown;
    };
    return typeof payload.role === 'string' ? payload.role : null;
  } catch {
    return null;
  }
}

function buildStaleStudentJwt(identifier: string): string {
  const header = Buffer.from(JSON.stringify({ alg: 'HS256', typ: 'JWT' })).toString('base64url');
  const payload = Buffer.from(
    JSON.stringify({
      sub: identifier,
      role: 'ROLE_STUDENT',
      exp: 1,
      token_type: 'access',
    })
  ).toString('base64url');
  return `${header}.${payload}.invalid-signature`;
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
    // Page may be on about:blank or already closed during teardown
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

async function loginStudent(page: Page, registrationNumber: string, pin: string): Promise<void> {
  await page.goto('/');
  await page.locator('#student-identifier').fill(registrationNumber);
  await page.locator('#student-pin').fill(pin);
  await page.getByRole('button', { name: 'Login', exact: true }).click();
}

async function findStudentRow(page: Page, registrationNumber: string) {
  await expect(page.locator('table')).toBeVisible({ timeout: 15000 });
  await expect(page.locator('tbody tr').first()).toBeVisible({ timeout: 15000 });

  const row = page.locator('tbody tr', { hasText: registrationNumber });
  if ((await row.count()) > 0) {
    await expect(row.first()).toBeVisible();
    return row.first();
  }

  const pageIndicator = page.locator('text=/Page \\d+ of \\d+/');

  for (let rewind = 0; rewind < 20; rewind += 1) {
    const prevButton = page.getByRole('button', { name: 'Previous', exact: true });
    if ((await prevButton.count()) === 0 || (await prevButton.isDisabled())) {
      break;
    }
    const beforePage = (await pageIndicator.count()) > 0 ? await pageIndicator.innerText() : null;
    const prevResponse = page.waitForResponse(
      (res) => res.url().includes('/api/v1/admin/students') && res.request().method() === 'GET'
    );
    await prevButton.click();
    await prevResponse;
    if (beforePage !== null) {
      await expect(pageIndicator).not.toHaveText(beforePage, { timeout: 10000 });
    }
    if ((await row.count()) > 0) {
      await expect(row.first()).toBeVisible();
      return row.first();
    }
  }

  for (let pageAttempt = 0; pageAttempt < 20; pageAttempt += 1) {
    if ((await row.count()) > 0) {
      await expect(row.first()).toBeVisible();
      return row.first();
    }
    const nextButton = page.getByRole('button', { name: 'Next', exact: true });
    if ((await nextButton.count()) === 0 || (await nextButton.isDisabled())) {
      break;
    }
    const beforePage = (await pageIndicator.count()) > 0 ? await pageIndicator.innerText() : null;
    const nextResponse = page.waitForResponse(
      (res) => res.url().includes('/api/v1/admin/students') && res.request().method() === 'GET'
    );
    await nextButton.click();
    await nextResponse;
    if (beforePage !== null) {
      await expect(pageIndicator).not.toHaveText(beforePage, { timeout: 10000 });
    }
  }
  await expect(row.first()).toBeVisible({ timeout: 10000 });
  return row.first();
}

async function createStudentViaUi(
  page: Page,
  candidateName: string,
  preferExamSubjects = false
): Promise<{ registrationNumber: string; oneTimePin: string }> {
  await page.getByRole('button', { name: 'Register Student' }).click();
  await expect(page.getByRole('heading', { name: 'Register Student' })).toBeVisible();

  await expect(page.locator('form input[type="password"]')).toHaveCount(0);
  await expect(page.locator('form label', { hasText: /^PIN/i })).toHaveCount(0);
  await expect(page.locator('#create-student-center')).toHaveCount(0);
  await expect(page.locator('form')).not.toContainText(/Exam Center/i);

  await page.locator('#create-student-name').fill(candidateName);

  const subjectCheckboxes = page.locator('form input[type="checkbox"]');
  await expect(subjectCheckboxes.first()).toBeVisible({ timeout: 10000 });
  const checkboxCount = await subjectCheckboxes.count();
  expect(checkboxCount).toBeGreaterThan(0);

  let selectedAny = false;
  if (preferExamSubjects) {
    const preferredLabels = page.locator('form label', {
      hasText: /math|biology|english/i,
    });
    const preferredCount = await preferredLabels.count();
    for (let i = 0; i < preferredCount; i += 1) {
      const box = preferredLabels.nth(i).locator('input[type="checkbox"]');
      if (!(await box.isChecked())) {
        await box.check();
      }
      selectedAny = true;
    }
    expect(selectedAny).toBe(true);
  }
  if (!selectedAny) {
    await subjectCheckboxes.first().check();
  }

  await page.getByRole('button', { name: 'Create Student' }).click();

  const slip = page.getByTestId('one-time-pin-slip');
  await expect(slip).toBeVisible({ timeout: 15000 });
  await expect(slip).not.toContainText(/Exam Center/i);

  const oneTimePin = (await slip.getByTestId('revealed-one-time-pin').innerText()).trim();
  expect(oneTimePin).toMatch(/^\d{5}$/);

  const regText = (
    await slip.locator('div.font-mono.font-bold.text-slate-900').first().innerText()
  ).trim();
  expect(regText).toMatch(/^\d{6}[A-Z]{2}$/);

  await slip.getByRole('button', { name: 'Done', exact: true }).click();
  await expect(slip).toBeHidden();

  return { registrationNumber: regText, oneTimePin };
}

test.describe.serial('Auth, Identity & Secrets Hardening (Unmocked)', () => {
  test.beforeEach(({ browserName }) => {
    if (browserName === 'webkit') {
      test.slow();
    }
  });

  test('T037.1: Admin login omits hardcoded hints, sanitizes invalid auth errors, and cleanly logs out', async ({
    page,
  }) => {
    try {
      await page.goto('/admin');
      await expect(page.getByRole('heading', { name: 'Admin Login' })).toBeVisible();

      await expect(page.locator('#admin-identifier')).toHaveValue('');
      await expect(page.locator('#admin-password')).toHaveValue('');
      const pageText = await page.locator('body').innerText();
      expect(pageText).not.toContain('admin123');
      expect(pageText).not.toMatch(/e\.g\.\s*ADMIN/i);

      await page.locator('#admin-identifier').fill(ADMIN_ID);
      await page.locator('#admin-password').fill('wrong-pin-once');
      const badLoginPromise = page.waitForResponse(
        (res) => res.url().includes('/api/v1/auth/login') && res.request().method() === 'POST'
      );
      await page.getByRole('button', { name: 'Login', exact: true }).click();
      const badRes = await badLoginPromise;
      expect(badRes.status()).toBe(401);

      const alertBox = page.getByRole('alert');
      await expect(alertBox).toBeVisible();
      const alertText = await alertBox.innerText();
      expect(alertText).not.toMatch(/Exception|StackTrace|org\.springframework|SELECT\s+/i);

      await loginStaff(page, ADMIN_ID, ADMIN_PIN);
      const token = await page.evaluate(() => localStorage.getItem('token'));
      expect(decodeRoleFromJwt(token)).toBe('ROLE_ADMIN');

      await page.getByRole('button', { name: 'Logout' }).click();
      const confirmDialog = page.getByRole('alertdialog');
      await expect(confirmDialog).toBeVisible();
      await confirmDialog.getByRole('button', { name: 'Logout', exact: true }).click();

      await expect(page.getByRole('heading', { name: 'Admin Login' })).toBeVisible({
        timeout: 10000,
      });
      const tokenAfterLogout = await page.evaluate(() => localStorage.getItem('token'));
      expect(tokenAfterLogout).toBeNull();
    } finally {
      await logoutViaApi(page);
    }
  });

  test('T037.2: Q1 Single & Bulk CSV one-time PIN reveal, zero plaintext roster/API exposure, credential PDF exports & login stability, and PIN reset', async ({
    browser,
    page,
  }) => {
    const runTag = createRunTag();
    const candidateName = `Ada E2E ${runTag}`;
    const bulkCandidateName = `Bulk E2E ${runTag}`;

    const studentContext = await browser.newContext();
    const studentPage = await studentContext.newPage();

    try {
      const adminToken = await loginStaff(page, ADMIN_ID, ADMIN_PIN);
      await page.getByRole('link', { name: 'Students' }).click();
      await expect(page.getByRole('heading', { name: 'Students', exact: true })).toBeVisible();

      const headerRowText = await page.locator('thead tr').innerText();
      expect(headerRowText).not.toMatch(/Login PIN/i);
      expect(headerRowText).not.toMatch(/Exam Center/i);
      expect(headerRowText).toContain('Credential Status');
      expect(headerRowText).toContain('Session Status');

      const created = await createStudentViaUi(page, candidateName, true);
      const singleRow = await findStudentRow(page, created.registrationNumber);
      const singleRowText = await singleRow.innerText();
      expect(singleRowText).toContain('Hashed');
      expect(singleRowText).toContain('Offline');
      expect(singleRowText).not.toContain(created.oneTimePin);

      const studentsApiRes = await page.request.get('/api/v1/admin/students?page=0&size=50', {
        headers: { Authorization: `Bearer ${adminToken}` },
      });
      expect(studentsApiRes.status()).toBe(200);
      const studentsJsonText = await studentsApiRes.text();
      expect(studentsJsonText).not.toContain('"pin":');
      expect(studentsJsonText).not.toContain('"pinHash":');
      expect(studentsJsonText).not.toContain('"pinCiphertext":');
      expect(studentsJsonText).not.toContain('"examCenter"');
      expect(studentsJsonText).not.toContain(created.oneTimePin);

      await page.getByRole('button', { name: 'Bulk Register CSV' }).click();
      await expect(page.getByRole('heading', { name: 'Bulk Register CSV' })).toBeVisible();

      const firstSubjectChip = page
        .locator('div:has(> span:has-text("Your Created Subjects")) span.bg-slate-50')
        .first();
      const bulkSubject =
        (await firstSubjectChip.count()) > 0
          ? (await firstSubjectChip.innerText()).trim()
          : 'MATHEMATICS';

      const csvBody = `Name,Subject 1,Subject 2,Subject 3,Subject 4\n${bulkCandidateName},${bulkSubject},,,\n`;
      await page.locator('#bulk-csv-file').setInputFiles({
        name: `roster-${runTag}.csv`,
        mimeType: 'text/csv',
        buffer: Buffer.from(csvBody, 'utf8'),
      });
      await page.getByRole('button', { name: 'Upload CSV' }).click();

      await expect(page.getByRole('heading', { name: 'Upload Results' })).toBeVisible({
        timeout: 15000,
      });
      await expect(page.getByRole('button', { name: 'Download One-Time PINs (CSV)' })).toBeVisible();
      await expect(
        page.getByRole('button', { name: 'Download Batch Credentials (PDF)' })
      ).toBeVisible();
      const bulkSlip = page.getByTestId('one-time-pin-slip');
      await expect(bulkSlip).toBeVisible();
      const bulkPin = (await bulkSlip.getByTestId('revealed-one-time-pin').first().innerText()).trim();
      expect(bulkPin).toMatch(/^\d{5}$/);
      const bulkReg = (
        await bulkSlip.locator('div.font-mono.font-bold.text-slate-900').first().innerText()
      ).trim();
      expect(bulkReg).toMatch(/^\d{6}[A-Z]{2}$/);

      const batchPdfDownloadPromise = page.waitForEvent('download');
      await page.getByRole('button', { name: 'Download Batch Credentials (PDF)' }).click();
      const batchPdfDownload = await batchPdfDownloadPromise;
      expect(batchPdfDownload.suggestedFilename()).toBe('batch_credentials_roster.pdf');
      const batchPdfText = extractOpenPdfText(await readDownloadBuffer(batchPdfDownload));
      expect(batchPdfText).toContain('StudentPrep Examination Credential Roster');
      expect(batchPdfText).toContain(bulkCandidateName);
      expect(batchPdfText).toContain(bulkReg);
      expect(batchPdfText).toContain(bulkPin);
      expect(batchPdfText).not.toContain(created.registrationNumber);
      expect(batchPdfText).not.toMatch(/Exam Center/i);

      await page.getByRole('button', { name: 'Close', exact: true }).click();

      const bulkRow = await findStudentRow(page, bulkReg);
      expect(await bulkRow.innerText()).not.toContain(bulkPin);

      const csvDownloadPromise = page.waitForEvent('download');
      await page.getByRole('button', { name: 'Export Roster (CSV)' }).click();
      const csvDownload = await csvDownloadPromise;
      expect(csvDownload.suggestedFilename()).toBe('student_roster.csv');

      const exportedCsv = (await readDownloadBuffer(csvDownload)).toString('utf8');
      const exportedLines = exportedCsv.trim().split(/\r?\n/);
      expect(exportedLines[0]).not.toMatch(/pin|password/i);
      expect(exportedLines[0]).not.toMatch(/Exam Center/i);
      const exportedCandidateLine = exportedLines.find((line) =>
        line.includes(created.registrationNumber)
      );
      expect(exportedCandidateLine).toBeTruthy();
      expect(exportedCandidateLine).not.toContain(created.oneTimePin);
      expect(exportedCsv).not.toContain(bulkPin);

      const allPdfDownloadPromise1 = page.waitForEvent('download');
      await page.getByRole('button', { name: 'Download All Credentials (PDF)' }).click();
      const allPdfDownload1 = await allPdfDownloadPromise1;
      expect(allPdfDownload1.suggestedFilename()).toBe('student_credentials_roster.pdf');
      const allPdfText1 = extractOpenPdfText(await readDownloadBuffer(allPdfDownload1));
      expect(allPdfText1).toContain(created.registrationNumber);
      expect(allPdfText1).toContain(created.oneTimePin);
      expect(allPdfText1).toContain(bulkReg);
      expect(allPdfText1).toContain(bulkPin);
      expect(allPdfText1).not.toMatch(/Exam Center/i);

      // Repeat bulk credential PDF download preserves exact active PINs without rotation
      const allPdfDownloadPromise2 = page.waitForEvent('download');
      await page.getByRole('button', { name: 'Download All Credentials (PDF)' }).click();
      const allPdfDownload2 = await allPdfDownloadPromise2;
      expect(allPdfDownload2.suggestedFilename()).toBe('student_credentials_roster.pdf');
      const allPdfText2 = extractOpenPdfText(await readDownloadBuffer(allPdfDownload2));
      expect(allPdfText2).toContain(created.registrationNumber);
      expect(allPdfText2).toContain(created.oneTimePin);
      expect(allPdfText2).toContain(bulkReg);
      expect(allPdfText2).toContain(bulkPin);
      expect(allPdfText2).not.toMatch(/Exam Center/i);

      // Bulk-imported student can log in on / using the PIN printed in the batch/bulk PDFs
      await loginStudent(studentPage, bulkReg, bulkPin);
      await expect(studentPage).toHaveURL(/\/dashboard/, { timeout: 15000 });
      await expect(studentPage.locator('button', { hasText: 'Start Examination' })).toBeVisible();
      const bulkStudentToken = await studentPage.evaluate(() => localStorage.getItem('token'));
      expect(bulkStudentToken).toBeTruthy();
      const studentForbiddenBulkRes = await studentPage.request.get(
        '/api/v1/admin/students/credentials/pdf',
        { headers: { Authorization: `Bearer ${bulkStudentToken}` } }
      );
      expect(studentForbiddenBulkRes.status()).toBe(403);
      await logoutViaApi(studentPage);

      const refreshedSingleRow = await findStudentRow(page, created.registrationNumber);

      const slipDownloadPromise = page.waitForEvent('download');
      await refreshedSingleRow.getByRole('button', { name: 'Slip', exact: true }).click();
      const slipDownload = await slipDownloadPromise;
      expect(slipDownload.suggestedFilename()).toMatch(/-slip\.pdf$|slip\.pdf/i);
      const rowSlipPdfText = extractOpenPdfText(await readDownloadBuffer(slipDownload));
      expect(rowSlipPdfText).toContain(created.registrationNumber);
      expect(rowSlipPdfText).toContain(`Login PIN: ${created.oneTimePin}`);
      expect(rowSlipPdfText).not.toMatch(/Exam Center/i);

      await refreshedSingleRow.getByRole('button', { name: 'View Profile', exact: true }).click();
      await expect(page.getByText('Basic Info, Credentials & Session Status')).toBeVisible();
      await expect(page.getByText('Active (Credential PDF Ready)')).toBeVisible();
      await expect(page.getByText(/Exam Center/i)).toHaveCount(0);

      const modalSlipDownloadPromise = page.waitForEvent('download');
      await page.getByRole('button', { name: 'Download Credential PDF' }).click();
      const modalSlipDownload = await modalSlipDownloadPromise;
      expect(modalSlipDownload.suggestedFilename()).toBe(`${created.registrationNumber}-slip.pdf`);
      const modalSlipPdfText = extractOpenPdfText(await readDownloadBuffer(modalSlipDownload));
      expect(modalSlipPdfText).toContain(created.registrationNumber);
      expect(modalSlipPdfText).toContain(`Login PIN: ${created.oneTimePin}`);
      expect(modalSlipPdfText).not.toMatch(/Exam Center/i);

      await page.getByRole('button', { name: 'Regenerate PIN' }).click();
      const resetDialog = page.getByRole('alertdialog');
      await expect(resetDialog).toBeVisible();
      await resetDialog.getByRole('button', { name: 'Confirm Reset PIN' }).click();

      const resetSlip = page.getByTestId('one-time-pin-slip');
      await expect(resetSlip).toBeVisible({ timeout: 15000 });
      const newPin = (await resetSlip.getByTestId('revealed-one-time-pin').innerText()).trim();
      expect(newPin).toMatch(/^\d{5}$/);
      expect(newPin).not.toBe(created.oneTimePin);

      await resetSlip.getByRole('button', { name: 'Dismiss Slip' }).click();

      const updatedSlipDownloadPromise = page.waitForEvent('download');
      await page.getByRole('button', { name: 'Download Credential PDF' }).click();
      const updatedSlipDownload = await updatedSlipDownloadPromise;
      const updatedSlipPdfText = extractOpenPdfText(await readDownloadBuffer(updatedSlipDownload));
      expect(updatedSlipPdfText).toContain(`Login PIN: ${newPin}`);
      expect(updatedSlipPdfText).not.toContain(`Login PIN: ${created.oneTimePin}`);
      expect(updatedSlipPdfText).not.toMatch(/Exam Center/i);

      await page.getByRole('button', { name: 'Close student profile modal' }).click();

      const oldPinPromise = studentPage.waitForResponse(
        (res) => res.url().includes('/api/v1/auth/login') && res.request().method() === 'POST'
      );
      await loginStudent(studentPage, created.registrationNumber, created.oneTimePin);
      const oldPinRes = await oldPinPromise;
      expect(oldPinRes.status()).toBe(401);
      await expect(studentPage).toHaveURL(/\/$/);
      const badToken = await studentPage.evaluate(() => localStorage.getItem('token'));
      expect(badToken).toBeNull();

      await loginStudent(studentPage, created.registrationNumber, newPin);
      await expect(studentPage).toHaveURL(/\/dashboard/, { timeout: 15000 });
      await expect(studentPage.locator('button', { hasText: 'Start Examination' })).toBeVisible();
    } finally {
      await logoutViaApi(studentPage);
      await studentContext.close();
      await logoutViaApi(page);
    }
  });

  test('T037.3: Q2 Single-workstation active exam lock (409), same-device refresh & silent /refresh recovery, admin unlock & eviction', async ({
    browser,
    page: adminPage,
  }) => {
    const runTag = createRunTag();
    const contextA = await browser.newContext();
    const contextB = await browser.newContext();
    const pageA = await contextA.newPage();
    const pageB = await contextB.newPage();

    try {
      await loginStaff(adminPage, ADMIN_ID, ADMIN_PIN);
      await adminPage.getByRole('link', { name: 'Students' }).click();
      await expect(
        adminPage.getByRole('heading', { name: 'Students', exact: true })
      ).toBeVisible();

      const student = await createStudentViaUi(adminPage, `Exam Lock ${runTag}`, true);

      await loginStudent(pageA, student.registrationNumber, student.oneTimePin);
      await expect(pageA).toHaveURL(/\/dashboard/, { timeout: 15000 });
      await pageA.getByRole('button', { name: 'Start Examination' }).click();
      await expect(pageA).toHaveURL(/\/exam/, { timeout: 15000 });
      await expect(pageA.getByText(/Question 1 of/i)).toBeVisible({ timeout: 15000 });

      const acceptReloadDialog = (dialog: { accept: () => Promise<void> }) => {
        void dialog.accept();
      };
      pageA.on('dialog', acceptReloadDialog);
      await pageA.reload();
      await expect(pageA.getByText(/Question 1 of/i)).toBeVisible({ timeout: 15000 });

      await pageA.goto('/dashboard');
      await expect(pageA.locator('button', { hasText: 'Resume Examination' })).toBeVisible({
        timeout: 15000,
      });
      pageA.off('dialog', acceptReloadDialog);

      const staleToken = buildStaleStudentJwt(student.registrationNumber);
      await pageA.evaluate((tokenValue) => {
        localStorage.setItem('token', tokenValue);
      }, staleToken);

      const refreshCallPromise = pageA.waitForResponse(
        (res) => res.url().includes('/api/v1/auth/refresh') && res.request().method() === 'POST'
      );
      await pageA.reload();
      const refreshRes = await refreshCallPromise;
      expect(refreshRes.status()).toBe(200);

      await expect(pageA.locator('button', { hasText: 'Resume Examination' })).toBeVisible({
        timeout: 15000,
      });
      const refreshedToken = await pageA.evaluate(() => localStorage.getItem('token'));
      expect(refreshedToken).toBeTruthy();
      expect(refreshedToken).not.toBe(staleToken);

      await pageA.locator('button', { hasText: 'Resume Examination' }).click();
      await expect(pageA).toHaveURL(/\/exam/, { timeout: 15000 });
      await expect(pageA.getByText(/Question 1 of/i)).toBeVisible({ timeout: 15000 });

      await loginStudent(pageB, student.registrationNumber, student.oneTimePin);
      const conflictAlert = pageB.getByRole('alert');
      await expect(conflictAlert).toBeVisible({ timeout: 10000 });
      await expect(conflictAlert).toContainText(/active examination|another|already/i);
      await expect(pageB).toHaveURL(/\/$/);
      expect(await pageB.evaluate(() => localStorage.getItem('token'))).toBeNull();

      await adminPage.reload();
      const studentRow = await findStudentRow(adminPage, student.registrationNumber);
      await expect(studentRow).toContainText('Active Session');
      await studentRow.getByRole('button', { name: 'Unlock Session' }).click();

      const unlockDialog = adminPage.getByRole('alertdialog');
      await expect(unlockDialog).toBeVisible();
      await unlockDialog.getByRole('button', { name: 'Unlock Session' }).click();
      await expect(studentRow).toContainText('Offline', { timeout: 10000 });

      await loginStudent(pageB, student.registrationNumber, student.oneTimePin);
      await expect(pageB).toHaveURL(/\/dashboard/, { timeout: 15000 });
      await expect(pageB.locator('button', { hasText: 'Resume Examination' })).toBeVisible();
      await pageB.locator('button', { hasText: 'Resume Examination' }).click();
      await expect(pageB).toHaveURL(/\/exam/, { timeout: 15000 });
      await expect(pageB.getByText(/Question 1 of/i)).toBeVisible({ timeout: 15000 });

      await expect(pageA).toHaveURL(/\/\?error=session_expired/, { timeout: 20000 });
      await expect(pageA.getByText(/Session Ended/i)).toBeVisible();
      expect(await pageA.evaluate(() => localStorage.getItem('token'))).toBeNull();

      pageB.once('dialog', (dialog) => {
        void dialog.accept();
      });
      await pageB.reload();
      await expect(pageB.getByText(/Question 1 of/i)).toBeVisible({ timeout: 15000 });

      await pageB.getByRole('button', { name: 'Submit Final' }).click();
      const submitDialog = pageB.getByRole('alertdialog');
      await expect(submitDialog).toBeVisible();
      await submitDialog.getByRole('button', { name: 'Submit', exact: true }).click();
      await expect(pageB).toHaveURL(/\/result|\/$/, { timeout: 15000 });
    } finally {
      await logoutViaApi(pageA);
      await logoutViaApi(pageB);
      await contextA.close();
      await contextB.close();
      await logoutViaApi(adminPage);
    }
  });

  test('T037.4: RBAC enforces student portal isolation and ROLE_SUPERVISOR read-only restrictions (UI & API 403)', async ({
    browser,
    page: adminPage,
  }) => {
    const runTag = createRunTag();
    const studentContext = await browser.newContext();
    const supervisorContext = await browser.newContext();
    const studentPage = await studentContext.newPage();
    const supervisorPage = await supervisorContext.newPage();

    try {
      await loginStaff(adminPage, ADMIN_ID, ADMIN_PIN);
      await adminPage.getByRole('link', { name: 'Students' }).click();
      const student = await createStudentViaUi(adminPage, `RBAC E2E ${runTag}`);
      await logoutViaApi(adminPage);

      await loginStudent(studentPage, student.registrationNumber, student.oneTimePin);
      await expect(studentPage).toHaveURL(/\/dashboard/, { timeout: 15000 });
      await studentPage.goto('/admin/students');
      await expect(studentPage).toHaveURL(/\/dashboard/, { timeout: 10000 });
      await logoutViaApi(studentPage);

      const supToken = await loginStaff(supervisorPage, SUPERVISOR_ID, SUPERVISOR_PIN);
      expect(decodeRoleFromJwt(supToken)).toBe('ROLE_SUPERVISOR');

      await supervisorPage.getByRole('link', { name: 'Students' }).click();
      await expect(
        supervisorPage.getByRole('heading', { name: 'Students', exact: true })
      ).toBeVisible();

      await expect(supervisorPage.getByRole('button', { name: 'Register Student' })).toHaveCount(0);
      await expect(supervisorPage.getByRole('button', { name: 'Bulk Register CSV' })).toHaveCount(
        0
      );
      await expect(supervisorPage.getByRole('button', { name: 'Export Roster (CSV)' })).toHaveCount(
        0
      );
      await expect(
        supervisorPage.getByRole('button', { name: 'Download All Credentials (PDF)' })
      ).toHaveCount(0);
      await expect(supervisorPage.getByRole('button', { name: 'Unlock Session' })).toHaveCount(0);
      await expect(supervisorPage.getByRole('button', { name: 'Slip', exact: true })).toHaveCount(
        0
      );

      const targetRow = await findStudentRow(supervisorPage, student.registrationNumber);
      await targetRow.getByRole('button', { name: 'View Profile', exact: true }).click();
      await expect(
        supervisorPage.getByText('Basic Info, Credentials & Session Status')
      ).toBeVisible();

      await expect(supervisorPage.getByRole('button', { name: 'Edit Profile' })).toHaveCount(0);
      await expect(
        supervisorPage.getByRole('button', { name: 'Download Credential PDF' })
      ).toHaveCount(0);
      await expect(supervisorPage.getByRole('button', { name: 'Regenerate PIN' })).toHaveCount(0);
      await expect(
        supervisorPage.getByRole('button', { name: 'Unlock Active Session' })
      ).toHaveCount(0);
      await expect(supervisorPage.getByRole('button', { name: 'Delete Student' })).toHaveCount(0);

      const supCreateRes = await supervisorPage.request.post('/api/v1/admin/students', {
        headers: { Authorization: `Bearer ${supToken}` },
        data: { name: 'Unauthorized Create', subjectIds: [] },
      });
      expect(supCreateRes.status()).toBe(403);

      const supBulkPdfRes = await supervisorPage.request.get(
        '/api/v1/admin/students/credentials/pdf',
        { headers: { Authorization: `Bearer ${supToken}` } }
      );
      expect(supBulkPdfRes.status()).toBe(403);

      const supBatchPdfRes = await supervisorPage.request.post(
        '/api/v1/admin/students/credentials/pdf',
        {
          headers: { Authorization: `Bearer ${supToken}` },
          data: { registrationNumbers: [student.registrationNumber] },
        }
      );
      expect(supBatchPdfRes.status()).toBe(403);

      const listRes = await supervisorPage.request.get('/api/v1/admin/students?page=0&size=1', {
        headers: { Authorization: `Bearer ${supToken}` },
      });
      expect(listRes.status()).toBe(200);
      const listJson = (await listRes.json()) as { data?: Array<{ id: string }> };
      const firstId = listJson.data?.[0]?.id;
      expect(firstId).toBeTruthy();

      const supSlipRes = await supervisorPage.request.get(
        `/api/v1/admin/students/${firstId}/slip`,
        { headers: { Authorization: `Bearer ${supToken}` } }
      );
      expect(supSlipRes.status()).toBe(403);

      const supResetRes = await supervisorPage.request.post(
        `/api/v1/admin/students/${firstId}/reset-pin`,
        { headers: { Authorization: `Bearer ${supToken}` } }
      );
      expect(supResetRes.status()).toBe(403);

      const supUnlockRes = await supervisorPage.request.post(
        `/api/v1/admin/students/${firstId}/unlock-session`,
        { headers: { Authorization: `Bearer ${supToken}` } }
      );
      expect(supUnlockRes.status()).toBe(403);

      const supDeleteRes = await supervisorPage.request.delete(
        `/api/v1/admin/students/${firstId}`,
        { headers: { Authorization: `Bearer ${supToken}` } }
      );
      expect(supDeleteRes.status()).toBe(403);
    } finally {
      await logoutViaApi(studentPage);
      await logoutViaApi(supervisorPage);
      await studentContext.close();
      await supervisorContext.close();
      await logoutViaApi(adminPage);
    }
  });

  test('T037.5: Rate limiter triggers 429 lockout with Retry-After feedback on repeated invalid attempts', async ({
    browser,
    page: adminPage,
  }) => {
    const runTag = createRunTag();
    const attackerContext = await browser.newContext();
    const attackerPage = await attackerContext.newPage();

    try {
      await loginStaff(adminPage, ADMIN_ID, ADMIN_PIN);
      await adminPage.getByRole('link', { name: 'Students' }).click();
      const target = await createStudentViaUi(adminPage, `Lockout ${runTag}`);
      await logoutViaApi(adminPage);

      await attackerPage.goto('/');
      await attackerPage.locator('#student-identifier').fill(target.registrationNumber);

      for (let attempt = 1; attempt <= 5; attempt += 1) {
        await attackerPage.locator('#student-pin').fill('00000');
        const resPromise = attackerPage.waitForResponse(
          (res) => res.url().includes('/api/v1/auth/login') && res.request().method() === 'POST'
        );
        await attackerPage.getByRole('button', { name: 'Login', exact: true }).click();
        const res = await resPromise;
        expect(res.status()).toBe(401);
      }

      await attackerPage.locator('#student-pin').fill(target.oneTimePin);
      const lockedResPromise = attackerPage.waitForResponse(
        (res) => res.url().includes('/api/v1/auth/login') && res.request().method() === 'POST'
      );
      await attackerPage.getByRole('button', { name: 'Login', exact: true }).click();
      const lockedRes = await lockedResPromise;
      expect(lockedRes.status()).toBe(429);
      expect(lockedRes.headers()['retry-after']).toBeTruthy();

      const rateLimitAlert = attackerPage.getByRole('alert');
      await expect(rateLimitAlert).toBeVisible();
      await expect(rateLimitAlert).toContainText(/too many|try again/i);
    } finally {
      await logoutViaApi(attackerPage);
      await attackerContext.close();
      await logoutViaApi(adminPage);
    }
  });
});
