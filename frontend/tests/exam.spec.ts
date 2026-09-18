import { test, expect } from '@playwright/test';

test.describe('StudentPrep CBT Platform E2E Tests', () => {

  test.beforeEach(async ({ page }) => {
    await page.route('/api/v1/auth/login', async route => {
      await route.fulfill({ json: { data: { accessToken: "header.eyJyb2xlIjoiUk9MRV9TVFVERU5UIn0.signature" } } });
    });
    await page.route('/api/v1/exams/start', async route => {
      await route.fulfill({ json: { data: { shuffleSeed: 123 } } });
    });
    await page.route('/api/v1/exams/active/payload', async route => {
      await route.fulfill({ json: { data: { questions: [
        { id: 'q1', content: { text: 'Question 1', options: { 'o1': 'A', 'o2': 'C' } } }, 
        { id: 'q2', content: { text: 'Question 2', options: { 'o3': 'B', 'o4': 'D' } } }
      ], contexts: {} } } });
    });
    await page.route(/\/api\/v1\/exams\/active\/session/, async route => {
      await route.fulfill({ json: { data: { status: 'IN_PROGRESS', timeLeft: 7150, lastSyncedAt: 0 } } });
    });
    await page.route(/\/api\/v1\/exams\/active\/sync/, async route => {
      await route.fulfill({ json: { data: { success: true } } });
    });
    await page.route(/\/api\/v1\/exams\/active\/submit/, async route => {
      await route.fulfill({ json: { data: { success: true } } });
    });
    
    // Navigate to app and log in before every test
    await page.goto('/');
    await expect(page.locator('h1').filter({ hasText: 'StudentPrep Portal' })).toBeVisible();
    await page.getByPlaceholder('e.g. 12345678AB').fill('12345678AB');
    await page.locator('input[type="password"]').fill('password123');
    await page.getByRole('button', { name: 'Login' }).click();
    await expect(page.getByText('Student Dashboard')).toBeVisible();
    await page.getByRole('button', { name: /Start Examination|Resume Examination/i }).click();
    await expect(page.locator('h1').filter({ hasText: 'StudentPrep CBT' })).toBeVisible();
  });

  test('offline resilience and data rehydration', async ({ page, context }) => {
    await expect(page.getByText(/idle|synced/i, { exact: true })).toBeVisible();
    await page.getByText('A', { exact: true }).click();
    await context.setOffline(true);
    await page.getByRole('button', { name: 'Next Question' }).click();
    await page.getByText('B', { exact: true }).click();
    // Dispatch an 'online' event to immediately force the background syncer to fire.
    // Because context is offline, this sync will fail instantly and trigger the "Saving Locally" UI.
    await page.evaluate(() => window.dispatchEvent(new Event('online')));
    await expect(page.getByText(/Saving Locally/i)).toBeVisible();
    await context.setOffline(false);
  });

  test('anti-cheating tab switch termination', async ({ page }) => {
    await expect(page.getByText(/idle|synced/i, { exact: true })).toBeVisible();
    await page.evaluate(() => {
        window.dispatchEvent(new Event('blur'));
    });
    await page.waitForTimeout(500);
    await expect(page.getByText('Warning: Unpermitted Action')).toBeVisible();
    await page.getByRole('button', { name: 'I Understand' }).click();
    await page.evaluate(() => {
        window.dispatchEvent(new Event('blur'));
    });
    await page.waitForTimeout(500);
    await expect(page.getByText('Warning: Unpermitted Action')).toBeVisible();
    await page.getByRole('button', { name: 'I Understand' }).click();
    await page.evaluate(() => {
        window.dispatchEvent(new Event('blur'));
        document.dispatchEvent(new Event('visibilitychange'));
    });
    await page.waitForTimeout(500);
    await expect(page.getByText('Exam Terminated')).toBeVisible();
  });

  test('full happy path: login, start, answer, submit, view results', async ({ page }) => {
    await expect(page.getByText(/idle|synced/i, { exact: true })).toBeVisible();
    
    // Answer first question
    await page.getByText('A', { exact: true }).click();
    
    // Move to next question
    await page.getByRole('button', { name: 'Next Question' }).click();
    
    // Answer second question
    await page.getByText('B', { exact: true }).click();
    
    // Submit exam
    await page.getByRole('button', { name: 'Submit Final' }).first().click();
    
    // Confirm submission
    await page.getByRole('button', { name: 'Submit' }).click();
    
    // Verify toast success
    await expect(page.getByText('Exam Submitted Successfully')).toBeVisible();
    
    // Verify navigation to results page
    await expect(page).toHaveURL(/.*\/result/);
  });

  test('exam session resumption on back button and refresh', async ({ page }) => {
    await expect(page.getByText(/idle|synced/i, { exact: true })).toBeVisible();
    
    // Select answer for question 1
    await page.getByText('A', { exact: true }).click();
    await expect(page.getByText('1/2')).toBeVisible();
    
    // Navigate back to dashboard
    await page.goto('/dashboard');
    await expect(page.getByText('Student Dashboard')).toBeVisible();
    await expect(page.getByText('Active Examination In Progress')).toBeVisible();
    
    // Resume exam
    await page.getByRole('button', { name: /Resume Examination|Start Examination/i }).click();
    await expect(page.locator('h1').filter({ hasText: 'StudentPrep CBT' })).toBeVisible();
    await expect(page.getByText('1/2')).toBeVisible();
    
    // Perform hard refresh on exam page
    await page.reload();
    await expect(page.locator('h1').filter({ hasText: 'StudentPrep CBT' })).toBeVisible();
    await expect(page.getByText(/idle|synced/i, { exact: true })).toBeVisible();
    await expect(page.getByText('1/2')).toBeVisible();
  });

  test('single session per user: evicts when session active on another device', async ({ page }) => {
    await expect(page.getByText(/idle|synced/i, { exact: true })).toBeVisible();
    
    // Simulate concurrent login on another device returning 401
    await page.route('/api/v1/exams/active/session', async route => {
      await route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({ status: 401, message: 'Session active on another device' })
      });
    });

    // Trigger session check or wait for 5s heartbeat
    await page.waitForTimeout(6000);

    // Should be immediately evicted and redirected to login with session expired banner
    await expect(page).toHaveURL(/.*\/\?error=session_expired/);
    await expect(page.getByText(/Session Ended/i)).toBeVisible();
  });
});
