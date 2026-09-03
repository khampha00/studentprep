import { test, expect } from '@playwright/test';

test.describe('StudentPrep CBT Platform E2E Tests', () => {

  test.beforeEach(async ({ page }) => {
    // Navigate to app and log in before every test
    await page.goto('/');
    await expect(page.locator('h1').filter({ hasText: 'StudentPrep Portal' })).toBeVisible();
    await page.getByPlaceholder('e.g. 12345678AB').fill('12345678AB');
    await page.locator('input[type="password"]').fill('password123');
    await page.getByRole('button', { name: 'Start Exam' }).click();
    await expect(page.locator('h1').filter({ hasText: 'StudentPrep CBT' })).toBeVisible();
  });

  test('offline resilience and data rehydration', async ({ page, context }) => {
    await page.clock.install();
    await expect(page.getByText(/idle|synced/i, { exact: true })).toBeVisible();
    await page.getByText('A', { exact: true }).click();
    await context.setOffline(true);
    await page.getByRole('button', { name: 'Next Question' }).click();
    await page.getByText('B', { exact: true }).click();
    // Dispatch an 'online' event to immediately force the background syncer to fire.
    // Because context is offline, this sync will fail instantly and trigger the "Saving Locally" UI.
    await page.evaluate(() => window.dispatchEvent(new Event('online')));
    await expect(page.getByText(/Saving Locally/i)).toBeVisible();
    await page.reload();
    await expect(page.locator('h1').filter({ hasText: 'StudentPrep Portal' })).toBeVisible();
    await page.getByPlaceholder('e.g. 12345678AB').fill('12345678AB');
    await page.locator('input[type="password"]').fill('password123');
    await page.getByRole('button', { name: 'Start Exam' }).click();
    await expect(page.getByText(/Saving Locally/i)).toBeVisible();
    await context.setOffline(false);
    await expect(page.getByText(/synced/i, { exact: true })).toBeVisible({ timeout: 15000 });
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
    await expect(page.getByText('FLAGGED_TAB_SWITCH')).toBeVisible();
  });
});
