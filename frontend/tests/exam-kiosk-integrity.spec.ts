import { test, expect, type Page, type Route } from '@playwright/test';

const ADMIN_ID = process.env.E2E_ADMIN_ID || 'ADMIN-001';
const ADMIN_PIN = process.env.E2E_ADMIN_PIN || 'admin123';
const SUPERVISOR_ID = process.env.E2E_SUPERVISOR_ID || 'SUPERVISOR-001';
const SUPERVISOR_PIN = process.env.E2E_SUPERVISOR_PIN || 'sup123456';

function createRunTag(): string {
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 6)}`.toUpperCase();
}

async function readLocalExamStates(
  page: Page
): Promise<Array<{ ownerIdentifier?: string; syncPendingFinal?: boolean }>> {
  return page.evaluate(
    () =>
      new Promise<Array<{ ownerIdentifier?: string; syncPendingFinal?: boolean }>>((resolve, reject) => {
        const req = indexedDB.open('StudentPrepDB');
        req.onerror = () => reject(req.error);
        req.onsuccess = () => {
          const db = req.result;
          if (!db.objectStoreNames.contains('examStates')) {
            db.close();
            resolve([]);
            return;
          }
          const tx = db.transaction('examStates', 'readonly');
          const store = tx.objectStore('examStates');
          const getAll = store.getAll();
          getAll.onerror = () => {
            db.close();
            reject(getAll.error);
          };
          getAll.onsuccess = () => {
            const rows = (getAll.result || []) as Array<{
              ownerIdentifier?: string;
              syncPendingFinal?: boolean;
            }>;
            db.close();
            resolve(rows);
          };
        };
      })
  );
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
    // ignore
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

async function createStudentViaUi(
  page: Page,
  candidateName: string
): Promise<{ registrationNumber: string; oneTimePin: string }> {
  await page.goto('/admin');
  await expect(page.getByRole('link', { name: 'Students' })).toBeVisible({ timeout: 15000 });
  await page.getByRole('link', { name: 'Students' }).click();
  await expect(page.getByRole('heading', { name: 'Students', exact: true })).toBeVisible();

  await page.getByRole('button', { name: 'Register Student' }).click();
  await expect(page.getByRole('heading', { name: 'Register Student' })).toBeVisible();

  await page.locator('#create-student-name').fill(candidateName);

  const subjectCheckboxes = page.locator('form input[type="checkbox"]');
  await expect(subjectCheckboxes.first()).toBeVisible({ timeout: 10000 });

  const preferredLabels = page.locator('form label', {
    hasText: /english|mathematics|accounting|biology|physics/i,
  });
  const preferredCount = await preferredLabels.count();
  let selectedAny = false;
  for (let i = 0; i < preferredCount; i += 1) {
    const box = preferredLabels.nth(i).locator('input[type="checkbox"]');
    if (!(await box.isChecked())) {
      await box.check();
    }
    selectedAny = true;
  }
  if (!selectedAny) {
    await subjectCheckboxes.first().check();
  }

  await page.getByRole('button', { name: 'Create Student' }).click();

  const slip = page.getByTestId('one-time-pin-slip');
  await expect(slip).toBeVisible({ timeout: 15000 });

  const oneTimePin = (await slip.getByTestId('revealed-one-time-pin').innerText()).trim();
  const regText = (
    await slip.locator('div.font-mono.font-bold.text-slate-900').first().innerText()
  ).trim();

  await slip.getByRole('button', { name: 'Done', exact: true }).click();
  await expect(slip).toBeHidden();

  return { registrationNumber: regText, oneTimePin };
}

test.describe.serial('Exam Kiosk Isolation, Offline PWA & Integrity E2E (Unmocked)', () => {
  test.beforeEach(({ browserName }) => {
    if (browserName === 'webkit') {
      test.slow();
    }
  });

  test('T026.1: Shared-Kiosk Browser Isolation (Student A -> Logout -> Student B Isolation)', async ({
    page,
  }) => {
    const runTag = createRunTag();
    const studentAName = `Kiosk A ${runTag}`;
    const studentBName = `Kiosk B ${runTag}`;

    try {
      // 1. Staff logs in and registers Student A and Student B
      await loginStaff(page, ADMIN_ID, ADMIN_PIN);
      const studentA = await createStudentViaUi(page, studentAName);
      const studentB = await createStudentViaUi(page, studentBName);

      // Logout Admin
      await logoutViaApi(page);

      // 2. Student A logs in on this browser
      await loginStudent(page, studentA.registrationNumber, studentA.oneTimePin);
      await expect(page.getByText('Examination Portal', { exact: true })).toBeVisible({
        timeout: 15000,
      });

      // Start exam
      await page.getByRole('button', { name: /Start Examination/i }).click();
      await expect(page.locator('header')).toContainText(studentA.registrationNumber, {
        timeout: 15000,
      });

      // Answer first available option
      const optionsA = page.locator('button, div').filter({ hasText: /^[A-D]$/ });
      if ((await optionsA.count()) > 0) {
        await optionsA.first().click();
      }

      // Verify IndexedDB state exists for Student A
      const statesA = await readLocalExamStates(page);
      const localOwnerA = statesA.length > 0 ? statesA[0].ownerIdentifier : null;
      expect(localOwnerA).toBe(studentA.registrationNumber);

      // Student A logs out from exam / dashboard
      await page.goto('/dashboard');
      await page.getByRole('button', { name: 'Logout' }).click();
      await expect(page.locator('#student-identifier')).toBeVisible({ timeout: 10000 });

      // 3. Student B logs in on the exact same browser page
      await loginStudent(page, studentB.registrationNumber, studentB.oneTimePin);
      await expect(page.getByText('Examination Portal', { exact: true })).toBeVisible({
        timeout: 15000,
      });

      // Verify that Student A's exam data was wiped upon identity switch
      const statesB = await readLocalExamStates(page);
      const localOwnerB = statesB.length > 0 ? statesB[0].ownerIdentifier : null;
      expect(localOwnerB).not.toBe(studentA.registrationNumber);

      // Student B starts their own fresh exam
      await page.getByRole('button', { name: /Start Examination/i }).click();
      await expect(page.locator('header')).toContainText(studentB.registrationNumber, {
        timeout: 15000,
      });

      // Verify Student B's name is rendered in candidate header
      await expect(page.locator('header')).toContainText(studentBName);
    } finally {
      await logoutViaApi(page);
    }
  });

  test('T026.2: Offline Reload with Zero External CDN Requests', async ({
    context,
    page,
    browserName,
  }) => {
    const runTag = createRunTag();
    const candidateName = `Offline CDN ${runTag}`;
    const offlineApiHandler = (route: Route) => route.abort('internetdisconnected');

    try {
      await loginStaff(page, ADMIN_ID, ADMIN_PIN);
      const student = await createStudentViaUi(page, candidateName);
      await logoutViaApi(page);

      // Track network requests to ensure NO requests leak to Google Fonts CDN or external CDNs
      const externalRequests: string[] = [];
      page.on('request', (req) => {
        const url = req.url();
        if (
          url.includes('fonts.googleapis.com') ||
          url.includes('fonts.gstatic.com') ||
          url.includes('placehold.co')
        ) {
          externalRequests.push(url);
        }
      });

      // Student logs in and enters exam
      await loginStudent(page, student.registrationNumber, student.oneTimePin);
      await expect(page.getByText('Examination Portal', { exact: true })).toBeVisible({
        timeout: 15000,
      });

      await page.getByRole('button', { name: /Start Examination/i }).click();
      await expect(page.locator('header')).toContainText(student.registrationNumber, {
        timeout: 15000,
      });

      // Assert 0 external font or placeholder requests during online load
      expect(externalRequests).toHaveLength(0);

      // Ensure service worker precache has registered and controls the client
      await page.waitForFunction(
        async () => {
          if (!('serviceWorker' in navigator)) return false;
          await navigator.serviceWorker.ready;
          return Boolean(navigator.serviceWorker.controller);
        },
        { timeout: 15000 }
      );

      // Simulate network disconnection (Playwright WebKit WinCairo cancels top-level reload before SW under setOffline and rejects **/* interception via Web Inspector)
      if (browserName === 'webkit') {
        const cachedAssets = await page.evaluate(async () => {
          const cacheNames = await caches.keys();
          const allUrls: string[] = [];
          for (const name of cacheNames) {
            const cache = await caches.open(name);
            const keys = await cache.keys();
            keys.forEach((req) => allUrls.push(req.url));
          }
          return allUrls;
        });
        expect(cachedAssets.some((u) => u.includes('/index.html'))).toBe(true);
        await context.route('**/api/**', offlineApiHandler);
        await context.route(/^https?:\/\/(?!localhost:5173|127\.0\.0\.1:5173)/, offlineApiHandler);
      } else {
        await context.setOffline(true);
      }

      // Reload `/exam` while offline
      await page.reload();

      // Verify exam rehydrates cleanly from Dexie offline cache without crashing
      await expect(page.locator('header')).toContainText(student.registrationNumber, {
        timeout: 15000,
      });
      await expect(page.getByText('Instructions')).toBeVisible();

      // Ensure zero external CDN calls were attempted offline
      expect(externalRequests).toHaveLength(0);

      // Restore network
      if (browserName === 'webkit') {
        await context.unroute('**/api/**', offlineApiHandler);
        await context.unroute(/^https?:\/\/(?!localhost:5173|127\.0\.0\.1:5173)/, offlineApiHandler);
      } else {
        await context.setOffline(false);
      }
    } finally {
      if (browserName === 'webkit') {
        await context.unroute('**/api/**', offlineApiHandler);
        await context.unroute(/^https?:\/\/(?!localhost:5173|127\.0\.0\.1:5173)/, offlineApiHandler);
      }
      await context.setOffline(false);
      await logoutViaApi(page);
    }
  });

  test('T026.3: Offline Answer Persistence & Verified Submission Recovery', async ({
    context,
    page,
  }) => {
    const runTag = createRunTag();
    const candidateName = `Offline Submit ${runTag}`;

    try {
      await loginStaff(page, ADMIN_ID, ADMIN_PIN);
      const student = await createStudentViaUi(page, candidateName);
      await logoutViaApi(page);

      await loginStudent(page, student.registrationNumber, student.oneTimePin);
      await page.getByRole('button', { name: /Start Examination/i }).click();
      await expect(page.locator('header')).toContainText(student.registrationNumber, {
        timeout: 15000,
      });

      // Answer question online
      const options = page.locator('button, div').filter({ hasText: /^[A-D]$/ });
      if ((await options.count()) > 0) {
        await options.first().click();
      }

      // Drop network connection
      await context.setOffline(true);

      // Select another answer offline
      if ((await options.count()) > 1) {
        await options.nth(1).click();
      }

      // Attempt submit while offline
      await page.getByRole('button', { name: 'Submit Final' }).click();
      await page.getByRole('button', { name: 'Submit' }).click();

      // Verified: must NOT navigate to `/result` prematurely
      await expect(page).not.toHaveURL(/.*\/result/);

      // Verified: shows honest offline notification
      await expect(page.getByText(/Offline.*Submission Queued/i)).toBeVisible({ timeout: 10000 });

      // Check that syncPendingFinal flag is persisted in IndexedDB
      const pendingStates = await readLocalExamStates(page);
      const pendingFinal = pendingStates.length > 0 ? Boolean(pendingStates[0].syncPendingFinal) : false;
      expect(pendingFinal).toBe(true);

      // Restore connection
      await context.setOffline(false);

      // Re-trigger submit online
      await page.getByRole('button', { name: 'Submit Final' }).click();
      await page.getByRole('button', { name: 'Submit' }).click();

      // Now verified 200 navigation to result page succeeds
      await expect(page).toHaveURL(/.*\/result/, { timeout: 15000 });
      await expect(
        page
          .locator('[data-slot="card-title"]')
          .filter({ hasText: /Exam Submitted Successfully|Submission Pending Review/i })
      ).toBeVisible();
    } finally {
      await context.setOffline(false);
      await logoutViaApi(page);
    }
  });

  test('T026.4: Anti-Malpractice Kiosk Termination and Automatic Clean Logout', async ({
    page,
  }) => {
    const runTag = createRunTag();
    const candidateName = `Malpractice ${runTag}`;

    try {
      await loginStaff(page, ADMIN_ID, ADMIN_PIN);
      const student = await createStudentViaUi(page, candidateName);
      await logoutViaApi(page);

      await loginStudent(page, student.registrationNumber, student.oneTimePin);
      await page.getByRole('button', { name: /Start Examination/i }).click();
      await expect(page.locator('header')).toContainText(student.registrationNumber, {
        timeout: 15000,
      });

      // Strike 1
      await page.evaluate(() => window.dispatchEvent(new Event('blur')));
      await expect(page.getByText(/Warning: Unpermitted Action/i)).toBeVisible({ timeout: 5000 });
      await page.getByRole('button', { name: 'I Understand' }).click();

      // Strike 2
      await page.evaluate(() => window.dispatchEvent(new Event('blur')));
      await expect(page.getByText(/Warning: Unpermitted Action/i)).toBeVisible({ timeout: 5000 });
      await page.getByRole('button', { name: 'I Understand' }).click();

      // Strike 3 -> Automatic termination
      await page.evaluate(() => {
        window.dispatchEvent(new Event('blur'));
        document.dispatchEvent(new Event('visibilitychange'));
      });

      // Verify Malpractice termination dialog appears
      await expect(page.getByRole('heading', { name: 'Exam Terminated' })).toBeVisible({
        timeout: 10000,
      });
      await expect(page.getByText(/You will be logged out in 3 seconds/i)).toBeVisible();

      // Verify automated logout and redirection to login page with terminated banner
      await expect(page).toHaveURL(/.*\?terminated=malpractice/, { timeout: 15000 });

      // Verify local storage token was wiped
      const token = await page.evaluate(() => localStorage.getItem('token'));
      expect(token).toBeNull();
    } finally {
      await logoutViaApi(page);
    }
  });

  test('T026.5: Admin Dashboard Recent Completed Exams and Flagged Moderation Queue', async ({
    page,
  }) => {
    try {
      // 1. Login as Supervisor (read-only verification)
      await loginStaff(page, SUPERVISOR_ID, SUPERVISOR_PIN);

      await expect(page.getByRole('heading', { name: 'Admin Dashboard' })).toBeVisible();
      await expect(page.getByText(/Pending Moderation & Flagged Sessions/i)).toBeVisible({
        timeout: 10000,
      });

      // Supervisor sees read-only badge
      await expect(page.getByText(/Read-only \(Supervisor\)/i)).toBeVisible();

      // Verify Recent Completed Exams accordion is functional
      const recentTrigger = page.locator('span', { hasText: 'Recent Completed Exams' });
      await expect(recentTrigger).toBeVisible();

      await logoutViaApi(page);

      // 2. Login as Admin (active control verification)
      await loginStaff(page, ADMIN_ID, ADMIN_PIN);
      await expect(page.getByRole('heading', { name: 'Admin Dashboard' })).toBeVisible();

      // Admin does NOT have the read-only supervisor restriction
      await expect(page.getByText(/Read-only \(Supervisor\)/i)).toHaveCount(0);

      // Verify Results Page moderation panel
      await page.goto('/admin/results');
      await expect(page.getByRole('heading', { name: 'Exam Results & Moderation' })).toBeVisible({
        timeout: 10000,
      });
      await expect(page.getByText(/Flagged Sessions & Moderation Queue/i)).toBeVisible();
    } finally {
      await logoutViaApi(page);
    }
  });
});
