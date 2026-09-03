# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: exam.spec.ts >> StudentPrep CBT Platform E2E Tests >> anti-cheating tab switch termination
- Location: tests\exam.spec.ts:36:3

# Error details

```
Error: expect(locator).toBeVisible() failed

Locator: getByText('Warning: Unpermitted Action')
Expected: visible
Timeout: 5000ms
Error: element(s) not found

Call log:
  - Expect "toBeVisible" with timeout 5000ms
  - waiting for getByText('Warning: Unpermitted Action')

```

```yaml
- region "Notifications alt+T"
- 'alertdialog "Warning: Exam Environment Left"':
  - 'heading "Warning: Exam Environment Left" [level=2]'
  - paragraph:
    - text: You have clicked outside the exam window or switched tabs. This is a violation of exam rules.
    - strong: "Strikes: 1 / 3"
    - text: If you reach 3 strikes, your exam will be automatically submitted and flagged for malpractice.
  - button "I Understand"
```

# Test source

```ts
  1  | import { test, expect } from '@playwright/test';
  2  | 
  3  | test.describe('StudentPrep CBT Platform E2E Tests', () => {
  4  | 
  5  |   test.beforeEach(async ({ page }) => {
  6  |     // Navigate to app and log in before every test
  7  |     await page.goto('/');
  8  |     await expect(page.locator('h1').filter({ hasText: 'StudentPrep Portal' })).toBeVisible();
  9  |     await page.getByPlaceholder('e.g. 12345678AB').fill('12345678AB');
  10 |     await page.locator('input[type="password"]').fill('password123');
  11 |     await page.getByRole('button', { name: 'Start Exam' }).click();
  12 |     await expect(page.locator('h1').filter({ hasText: 'StudentPrep CBT' })).toBeVisible();
  13 |   });
  14 | 
  15 |   test('offline resilience and data rehydration', async ({ page, context }) => {
  16 |     await page.clock.install();
  17 |     await expect(page.getByText(/idle|synced/i, { exact: true })).toBeVisible();
  18 |     await page.getByText('A', { exact: true }).click();
  19 |     await context.setOffline(true);
  20 |     await page.getByRole('button', { name: 'Next Question' }).click();
  21 |     await page.getByText('B', { exact: true }).click();
  22 |     // Dispatch an 'online' event to immediately force the background syncer to fire.
  23 |     // Because context is offline, this sync will fail instantly and trigger the "Saving Locally" UI.
  24 |     await page.evaluate(() => window.dispatchEvent(new Event('online')));
  25 |     await expect(page.getByText(/Saving Locally/i)).toBeVisible();
  26 |     await page.reload();
  27 |     await expect(page.locator('h1').filter({ hasText: 'StudentPrep Portal' })).toBeVisible();
  28 |     await page.getByPlaceholder('e.g. 12345678AB').fill('12345678AB');
  29 |     await page.locator('input[type="password"]').fill('password123');
  30 |     await page.getByRole('button', { name: 'Start Exam' }).click();
  31 |     await expect(page.getByText(/Saving Locally/i)).toBeVisible();
  32 |     await context.setOffline(false);
  33 |     await expect(page.getByText(/synced/i, { exact: true })).toBeVisible({ timeout: 15000 });
  34 |   });
  35 | 
  36 |   test('anti-cheating tab switch termination', async ({ page }) => {
  37 |     await expect(page.getByText(/idle|synced/i, { exact: true })).toBeVisible();
  38 |     await page.evaluate(() => {
  39 |         window.dispatchEvent(new Event('blur'));
  40 |     });
  41 |     await page.waitForTimeout(500);
> 42 |     await expect(page.getByText('Warning: Unpermitted Action')).toBeVisible();
     |                                                                 ^ Error: expect(locator).toBeVisible() failed
  43 |     await page.getByRole('button', { name: 'I Understand' }).click();
  44 |     await page.evaluate(() => {
  45 |         window.dispatchEvent(new Event('blur'));
  46 |     });
  47 |     await page.waitForTimeout(500);
  48 |     await expect(page.getByText('Warning: Unpermitted Action')).toBeVisible();
  49 |     await page.getByRole('button', { name: 'I Understand' }).click();
  50 |     await page.evaluate(() => {
  51 |         window.dispatchEvent(new Event('blur'));
  52 |         document.dispatchEvent(new Event('visibilitychange'));
  53 |     });
  54 |     await page.waitForTimeout(500);
  55 |     await expect(page.getByText('Exam Terminated')).toBeVisible();
  56 |     await expect(page.getByText('FLAGGED_TAB_SWITCH')).toBeVisible();
  57 |   });
  58 | });
  59 | 
```