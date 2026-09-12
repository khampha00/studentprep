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

Locator: getByText('FLAGGED_TAB_SWITCH')
Expected: visible
Timeout: 5000ms
Error: element(s) not found

Call log:
  - Expect "toBeVisible" with timeout 5000ms
  - waiting for getByText('FLAGGED_TAB_SWITCH')

```

```yaml
- region "Notifications alt+T"
- alertdialog "Exam Terminated":
  - heading "Exam Terminated" [level=2]
  - paragraph: Your exam has been forcefully submitted due to multiple rule violations (Tab Switching). This attempt has been flagged for administrative review.
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
  26 |     await context.setOffline(false);
  27 |     await page.reload();
  28 |     await expect(page.locator('h1').filter({ hasText: 'StudentPrep Portal' })).toBeVisible();
  29 |     await page.getByPlaceholder('e.g. 12345678AB').fill('12345678AB');
  30 |     await page.locator('input[type="password"]').fill('password123');
  31 |     await page.getByRole('button', { name: 'Start Exam' }).click();
  32 |     await expect(page.getByText(/Saving Locally/i)).toBeVisible();
  33 |     await context.setOffline(false);
  34 |     await expect(page.getByText(/synced/i, { exact: true })).toBeVisible({ timeout: 15000 });
  35 |   });
  36 | 
  37 |   test('anti-cheating tab switch termination', async ({ page }) => {
  38 |     await expect(page.getByText(/idle|synced/i, { exact: true })).toBeVisible();
  39 |     await page.evaluate(() => {
  40 |         window.dispatchEvent(new Event('blur'));
  41 |     });
  42 |     await page.waitForTimeout(500);
  43 |     await expect(page.getByText('Warning: Unpermitted Action')).toBeVisible();
  44 |     await page.getByRole('button', { name: 'I Understand' }).click();
  45 |     await page.evaluate(() => {
  46 |         window.dispatchEvent(new Event('blur'));
  47 |     });
  48 |     await page.waitForTimeout(500);
  49 |     await expect(page.getByText('Warning: Unpermitted Action')).toBeVisible();
  50 |     await page.getByRole('button', { name: 'I Understand' }).click();
  51 |     await page.evaluate(() => {
  52 |         window.dispatchEvent(new Event('blur'));
  53 |         document.dispatchEvent(new Event('visibilitychange'));
  54 |     });
  55 |     await page.waitForTimeout(500);
> 56 |     await expect(page.getByText('Exam Terminated')).toBeVisible();
     |                                                        ^ Error: expect(locator).toBeVisible() failed
  57 |     await expect(page.getByText('FLAGGED_TAB_SWITCH')).toBeVisible();
  58 |   });
  59 | });
  60 | 
```